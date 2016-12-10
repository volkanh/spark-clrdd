package de.qaware.chronix

import org.jocl.CL._
import org.jocl._

import org.cache2k.{Cache, Cache2kBuilder}
import org.cache2k.integration.CacheLoader

import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}

import scala.math.Numeric
import scala.math.Ordering

import java.util.HashMap
import java.util.PrimitiveIterator
import java.lang.ref.{ReferenceQueue, WeakReference}
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer

class OpenCLSession (val context: cl_context, val queue: cl_command_queue, val device: cl_device_id)
{
  import OpenCL.{Program, Chunk, KernelArg, Dimensions, safeReleaseEvent}
  val log = LoggerFactory.getLogger(getClass)
  val executionTime = new AtomicLong
  log.info("created OpenCLSession")

  case class ChunkIterator[T](chunk: Chunk[T])(implicit clT: CLType[T])
    extends Iterator[T] with java.io.Closeable
  {
    private val Chunk(elems, _, handle, inputReady) = chunk
    clRetainMemObject(handle)
    clRetainEvent(inputReady)
    var outputReady : cl_event = null
    var closed = false
    private var rawBuffer: Option[ByteBuffer] = None
    private var mappedOffset = 0L
    private var idx = 0L
    private def ensureMapped(idx: Long)(implicit clT: CLType[T]) = {
      val maxMapSize = 1024L*1024*64 // implicit assumption is that all value sizes divide this size
      val address = idx * clT.sizeOf
      if(rawBuffer.isEmpty || address < mappedOffset || mappedOffset + maxMapSize <= address) {
        unmap()
        mappedOffset = address / maxMapSize * maxMapSize
        val mapSize = Math.min(maxMapSize, clT.sizeOf * elems - mappedOffset)
        outputReady = new cl_event
        log.trace("mapping {} for iteration at offset {}, size {}", handle, mappedOffset.asInstanceOf[AnyRef], mapSize.asInstanceOf[AnyRef])
        rawBuffer = Some(clEnqueueMapBuffer(queue, handle, false, CL_MAP_READ, mappedOffset, mapSize, 1, Array(inputReady), outputReady, null))
      }
    }
    override def hasNext = {
      val res = (idx != elems)
      if(!res)
        close
      res
    }
    override def next() = {
      ensureMapped(idx)
      if(outputReady != new cl_event) {
        clWaitForEvents(1, Array(outputReady))
        safeReleaseEvent(outputReady)
        outputReady = new cl_event
      }
      idx += 1
      clT.fromByteBuffer((idx-1-mappedOffset/clT.sizeOf).toInt, rawBuffer.get)
    }
    private def unmap() : Unit = {
      rawBuffer.foreach(b => {
        clEnqueueUnmapMemObject(queue, handle, b, 0, null, null)
        safeReleaseEvent(outputReady)
      })
      rawBuffer = None
    }
    override def close() : Unit = {
      if(closed) return
      closed = true
      rawBuffer.foreach(b => {
        clEnqueueUnmapMemObject(queue, handle, b, 0, null, null)
        safeReleaseEvent(outputReady)
      })
      clReleaseMemObject(handle)
      safeReleaseEvent(inputReady)
      rawBuffer = None
    }
    override def finalize(): Unit = close
  }

  private val programCache : Cache[CLProgramSource, Program] = Cache2kBuilder.of(classOf[CLProgramSource], classOf[Program])
    .entryCapacity(100)
    .loader(new CacheLoader[CLProgramSource,Program](){
      override def load(key: CLProgramSource) : Program = {
        val source = key.generateSource
        val program = clCreateProgramWithSource(context, source.size, source.toArray, null, null)
        val res = Program(program) // finalize if buildProgram throws
        import org.apache.commons.lang.builder.ReflectionToStringBuilder
        if(log.isInfoEnabled)
          log.info("building program: {}", source.flatten)
        clBuildProgram(res.program, 0, null, "-cl-unsafe-math-optimizations", null, null)
      res
      }
    }).build

  def callKernel(programSource: CLProgramSource, kernelName: String, args: Seq[KernelArg], dependencies: Array[cl_event], dimensions: Dimensions, ready: cl_event) : Unit = {
    val startTime = System.nanoTime
    val program = programCache.get(programSource)
    val createTime = System.nanoTime
    log.trace("get Kernel took {}ms", (createTime - startTime)/1e6)
    var kernel : Option[cl_kernel] = None
    try {
      kernel = Some(clCreateKernel(program.program, kernelName, null))
      log.trace("createKernel took {}ms", (System.nanoTime - createTime)/1e6)

      var index = 0
      val argTime = System.nanoTime
      args.foreach({case KernelArg(value, size) => {
        clSetKernelArg(kernel.get, index, size, value)
        index += 1
      }})
      log.trace("KernelArgs took {}ms", (System.nanoTime - argTime)/1e6)

      val enTime = System.nanoTime
      clEnqueueNDRangeKernel(
        queue,
        kernel.get,
        dimensions.dim, dimensions.global_work_offset, dimensions.global_work_size, dimensions.local_work_size,
        if(dependencies != null) dependencies.size else 0,
        dependencies,
        ready
      )
      log.trace("clEnqueueNDRangeKernel {} took {}ms", ready, (System.nanoTime - enTime)/1e6)

      clSetEventCallback(ready, CL_COMPLETE, new ProfilingCallback(ready, Some(executionTime)), null)
    } finally {
      kernel.foreach(clReleaseKernel)
    }
  }

  /*
   * parallelization happens outside Chunks for CPU
   * for GPU this could be tuned (possibly depending on operation types)
   * values other than 1 will lead to wrong results on CPU (and wasted time)
   * nlocal is taken as an upper bound, depending on operand size
   */
  var ngroups : Long = if(OpenCL.CPU) 1 else 8*1024
  var nlocal : Long = if(OpenCL.CPU) 1 else 128

  /*
   * For some OpenCL implementations, allocating small buffers is quite
   * expensive (NVIDIA, I'm looking at you), even if they are freed again
   * rapidly. This is a free-list of 32*64KB = 2MB. Each reduce needs two of
   * them, one for the result and one for intermediate storage.
   */
  val dustSize = 8*8*1024
  val dustQueue = new java.util.concurrent.ConcurrentLinkedQueue[cl_mem]
  (1 to 32).foreach(_ => {
    val mem = clCreateBuffer(context, 0, dustSize, null, null)
    dustQueue.add(mem)
  })
  def retDust = dustQueue.add(_)
  def getDust = { var tmp : cl_mem = null
    tmp = dustQueue.poll()
    while(tmp == null) {
      Thread.sleep(10)
      tmp = dustQueue.poll()
    }
    tmp
  }

  def reduceChunk[A, B](input: Chunk[A], kernel: MapReduceKernel[A,B])(implicit clA: CLType[A], clB: CLType[B]): Future[B] = {
    val startTime = System.nanoTime
    assert(clB.sizeOf <= dustSize)
    val numWorkGroups = {
      var tmp = ngroups
      while(tmp * clB.sizeOf > dustSize) {
        tmp = tmp/2
      }
      tmp
    }
    val localSize = nlocal // TODO make this tunable / evaluate...?
    val globalSize = localSize * numWorkGroups
    val finished = new cl_event
    val ready1 = new cl_event
    val ready2 = new cl_event
    var reduceBuffer : cl_mem = null
    var resBuffer : cl_mem = null
    var callbackSet = false
    try {
      reduceBuffer = getDust
      resBuffer = getDust
      callKernel(
        kernel, "reduce",
        KernelArg(input.handle) :: KernelArg(reduceBuffer) :: KernelArg(null, clB.sizeOf * localSize) :: KernelArg(input.size) :: Nil,
        Array(input.ready),
        Dimensions(1, Array(0), Array(globalSize), Array(localSize)),
        ready1
      )
      callKernel(
        kernel.stage2, "reduce",
        KernelArg(reduceBuffer) :: KernelArg(resBuffer) :: KernelArg(null, clB.sizeOf * localSize) :: KernelArg(numWorkGroups) :: Nil,
        Array(ready1),
        Dimensions(1, Array(0), Array(localSize), Array(localSize)),
        ready2
      )
      val promise = Promise[B]
      val future = promise.future
      val result = ByteBuffer.allocateDirect(clB.sizeOf)
      clEnqueueReadBuffer(queue, resBuffer, false, 0, clB.sizeOf, Pointer.to(result), 1, Array(ready2), finished)
      clSetEventCallback(finished, CL_COMPLETE, new EventCallbackFunction(){
        override def function(event: cl_event, command_exec_callback_type: Int, user_data: AnyRef): Unit = {
          promise.success(clB.fromByteBuffer(0, result))
          retDust(reduceBuffer)
          retDust(resBuffer)
        }
      }, null)
      callbackSet = true
      future
    } finally {
      safeReleaseEvent(finished)
      safeReleaseEvent(ready1)
      safeReleaseEvent(ready2)
      if(!callbackSet) {
          retDust(reduceBuffer)
          retDust(resBuffer)
      }
      val endTime = System.nanoTime
      log.trace("reduce overhead took {}ms", (endTime - startTime)/1e6)
    }
  }

  /**
   * Map one Chunk to a new one.
   * @param functionBody the body of the map function 'inline B f(A x) {#functionBody}'.
   * @param destructive if true, mapping is done in place if possible
   */
  def mapChunk[A,B](input: Chunk[A], kernel: MapKernel[A,B], destructive: Boolean = false)(implicit clA: CLType[A], clB: CLType[B]) : Chunk[B] = {
    //could be done in place even if sizes dont line up, but with more complex code
    val inplace = destructive && (clA.sizeOf == clB.sizeOf)
    val dimensions = Dimensions(1, Array(0), Array(input.size), null)
    val ready = new cl_event
    var handle: Option[cl_mem] = None
    try {
      var realKernel = if(inplace) {
        InplaceMap(kernel)
      } else {
        kernel
      }
      val kernelArgs = new ArrayBuffer[KernelArg]
      kernelArgs += KernelArg(input.handle)
      if(!inplace) {
        handle = Some(clCreateBuffer(context, 0, input.size*clB.sizeOf, null, null))
        kernelArgs += KernelArg(handle.get)
      }
      callKernel(
        realKernel, "map",
        kernelArgs,
        Array(input.ready),
        dimensions,
        ready
      )
      if(inplace) {
        new Chunk[B](input.size, input.space, input.handle, ready)
      } else {
        new Chunk[B](input.size, input.size*clB.sizeOf, handle.get, ready)
      }
    } finally {
      safeReleaseEvent(ready)
      handle.foreach(clReleaseMemObject)
      if(destructive)
        input.close
    }
  }

  val ALLOC_HOST_PTR_ON_DEVICE = {
    /*
     * This decides if writing to a host mapped ALLOC_HOST_PTR buffer is
     * enough. If false, contents are first written to (pinned)
     * ALLOC_HOST_PTR_ON_DEVICE and then clEnqueueCopyBuffer to an 'ordinary'
     * device buffer. If the first buffer is already on the device this
     * effectively halves the usable accelerator memory. On the other hand just
     * using the first buffer if it does not reside on the device would reduce
     * (memory bound) computations to bus speed.
     * TODO make a better choice here, CL_DEVICE_HOST_UNIFIED_MEMORY is deprecated
     */
    val buffer = Array(0)
    clGetDeviceInfo(device, CL_DEVICE_HOST_UNIFIED_MEMORY, Sizeof.cl_int, Pointer.to(buffer), null)
    val vendorBuffer = new Array[Byte](1024)
    clGetDeviceInfo(device, CL_DEVICE_VENDOR, 1024, Pointer.to(vendorBuffer), null)
    val vendor = new String(vendorBuffer, "UTF-8")
    val res = (buffer(0) != 0) || vendor.toLowerCase.matches(".*nvidia.*")
    log.info(s"${if (!res) "not " else ""}using unified memory")
    res
  }

  /**
   * Put the contents of an iterator on the gpu in constant sized chunks (default 128MB size).
   * Chunks have to be closed after use
   */
  def stream[T](it: Iterator[T], groupSize: Int = 1024*1024*256)(implicit clT: CLType[T]) : Iterator[Chunk[T]] = new Iterator[Chunk[T]](){
    override def hasNext = it.hasNext
    override def next = {
      var on_device : Option[cl_mem] = None
      var on_host : Option[cl_mem] = None
      var unmapEvent = new cl_event
      var readyEvent: cl_event = null
      var allocatedSize : Int = 0
      try {
        // Allocating direct buffers via ByteBuffer is prone to oom problems
        // it's faster anyway to refcount this
        //val rawBuffer = ByteBuffer.allocateDirect(groupSize)
        on_host = Some(clCreateBuffer(context, CL_MEM_ALLOC_HOST_PTR, groupSize, null, null))
        val rawBuffer = clEnqueueMapBuffer(queue, on_host.get, true, CL_MAP_WRITE_INVALIDATE_REGION, 0, groupSize, 0, null, null, null)
        log.trace("mapped buffer {}", on_host.get)
        var copied = 0
        while(copied < groupSize/clT.sizeOf && it.hasNext) {
          clT.toByteBuffer(copied, rawBuffer, it.next)
          copied += 1
        }
        log.trace("unmapping buffer {}", on_host.get)
        clEnqueueUnmapMemObject(queue, on_host.get, rawBuffer, 0, null, unmapEvent)

        if(ALLOC_HOST_PTR_ON_DEVICE) {
          on_device = on_host
          readyEvent = unmapEvent
          unmapEvent = null
          on_host = None
          allocatedSize = groupSize
        } else {
          on_device = Some(clCreateBuffer(context, CL_MEM_READ_ONLY, copied*clT.sizeOf, null, null))
          readyEvent = new cl_event
          clEnqueueCopyBuffer(queue, on_host.get, on_device.get, 0, 0, copied*clT.sizeOf, 1, Array(unmapEvent), readyEvent)
          allocatedSize = copied*clT.sizeOf
        }
        Chunk[T](copied, allocatedSize, on_device.get, readyEvent)
      } finally {
        safeReleaseEvent(readyEvent)
        safeReleaseEvent(unmapEvent)
        on_host.foreach(clReleaseMemObject)
        on_device.foreach(clReleaseMemObject)
      }
    }
  }

  override def finalize = {
    log.info("finalizing OpenCLSession")
    clReleaseCommandQueue(queue)
    clReleaseContext(context)
  }
}
