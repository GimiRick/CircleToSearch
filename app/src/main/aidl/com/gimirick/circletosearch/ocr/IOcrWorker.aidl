package com.gimirick.circletosearch.ocr;
import android.os.SharedMemory;
import com.gimirick.circletosearch.ocr.IOcrWorkerCallback;
interface IOcrWorker {
    void submit(long requestId, String packId, in SharedMemory pixels, int width, int height, int rowBytes, IOcrWorkerCallback callback);
    void cancel(long requestId);
    void shutdown();
}
