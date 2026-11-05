package com.example.imageedit.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Single serialized worker for all bitmap / OpenCV / ONNX work.
 *
 * Keeping one dedicated thread:
 * - prevents concurrent pixel pipelines from multiplying peak memory;
 * - keeps heavy work off Dispatchers.IO (which is reserved for decode/file/network);
 * - lets EditSession's lock remain uncontended in production.
 *
 * The thread runs at background priority so UI rendering and input are not
 * starved by long edits.
 */
object ImagePipeline {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "image-pipeline").apply { isDaemon = true }
    }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
