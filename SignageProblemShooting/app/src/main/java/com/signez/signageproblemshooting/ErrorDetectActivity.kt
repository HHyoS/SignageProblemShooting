package com.signez.signageproblemshooting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.signez.signageproblemshooting.data.entities.Cabinet
import com.signez.signageproblemshooting.data.entities.ErrorModule
import com.signez.signageproblemshooting.data.entities.Signage
import com.signez.signageproblemshooting.ui.AppViewModelProvider
import com.signez.signageproblemshooting.ui.analysis.AnalysisProgress
import com.signez.signageproblemshooting.ui.analysis.AnalysisViewModel
import com.signez.signageproblemshooting.ui.theme.SignEzPrototypeTheme
import kotlinx.coroutines.*
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream


class SignageNotFoundException(message: String) : Exception(message)

class ErrorDetectActivity : ComponentActivity() {

    companion object {
        // constants
        private const val REQUEST_DETECT_VIDEO: Int = 100
        private const val REQUEST_DETECT_PHOTO: Int = 101
        private const val REQUEST_CODE_ERROR_DETECT_ACTIVITY = 999

        private val REQUEST_TYPE: String = "REQUEST_TYPE"
        private val REQUEST_SIGNAGE_ID: String = "REQUEST_SIGNAGE_ID"

        private val signageDetectModuleFileName: String = "signage_detect.torchscript.pt"
        private val errorDetectModuleFileName: String = "error_detect.torchscript.pt"

        private const val RESIZE_SIZE: Int = 640
        private val NO_MEAN_RGB = floatArrayOf(0.0f, 0.0f, 0.0f)
        private val NO_STD_RGB = floatArrayOf(1.0f, 1.0f, 1.0f)

        // model output is of size 25200*(num_of_class+5)
        private const val mOutputRow = 25200 // as decided by the YOLOv5 model for input image of size 640*640
        private const val mOutputColumn = 6 // left, top, right, bottom, score and class probability
        private const val scoreThreshold = 0.20f // score above which a detection is generated

        private const val rectThreshold = 5.0f

    }

    // Torch Modules
    private lateinit var signageDetectModule: Module
    private lateinit var errorDetectModule: Module


    private lateinit var analysisViewModel: AnalysisViewModel

    private lateinit var signage: Signage
    private lateinit var cabinet: Cabinet
    private lateinit var uri: Uri
    private var type: Int = -1




    fun getModel(): Module {
        return Module.load(assetFilePath(SignEzApplication.instance, signageDetectModuleFileName))
    }

    private fun detect() {
        lifecycleScope.launch {
            when (type) {
                REQUEST_DETECT_VIDEO -> detectVideo()
                REQUEST_DETECT_PHOTO -> detectPhoto()
                else -> {
                    Log.i("-------------State-----------", uri.toString())
                    Log.i("-------------State-----------", signage.toString())
                    Log.i("-------------State-----------", cabinet.toString())

                    delay(5000)
                    Log.e("ErrorDetectActivity", "Wrong Access to Activity!")

                    //
                }
            } // when end
            setResult(REQUEST_CODE_ERROR_DETECT_ACTIVITY)
            finish()
        }
    }

    private val mLoaderCallback: BaseLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                SUCCESS -> {
                    Log.i("OpenCV", "OpenCV loaded successfully")
                    detect()
                }

                else -> {
                    super.onManagerConnected(status)
                    Log.e("OpenCV", "OpenCV load failed!")
                    finishActivity(998)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        analysisViewModel = ViewModelProvider( // 분석 종합
            this,
            factory = AppViewModelProvider.Factory
        ).get(AnalysisViewModel::class.java)

        val intents: Intent = intent
        val nullableType = intents.extras?.getInt(REQUEST_TYPE)
        val signageId: Long? = intents.extras?.getLong(REQUEST_SIGNAGE_ID)

        setContent {
            SignEzPrototypeTheme {
                AnalysisProgress()
            }
        }

        try {
            signageDetectModule =
                Module.load(assetFilePath(applicationContext, signageDetectModuleFileName))
            errorDetectModule =
                Module.load(assetFilePath(applicationContext, errorDetectModuleFileName))
        } catch (e: Exception) {
            Log.e("TorchScriptModule", "Failed to open module files.")
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val intents: Intent = intent
        val nullableType = intents.extras?.getInt(REQUEST_TYPE)
        val signageId: Long? = intents.extras?.getLong(REQUEST_SIGNAGE_ID)
        if (intents.data == null || signageId == null || nullableType == null) {
            finish()
        }
        lifecycleScope.launch {
            uri = intents.data!!
            signage = analysisViewModel.getSignageById(signageId!!)
            cabinet = analysisViewModel.getCabinet(signageId)
            type = nullableType!!
            if (!OpenCVLoader.initDebug()) {
                Log.d("OpenCV", "onResume :: Internal OpenCV library not found.")
                OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, applicationContext, mLoaderCallback)
            } else {
                Log.d("OpenCV", "onResume :: OpenCV library found inside package. Using it!")
                mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
            }
        }
    }

    private suspend fun detectVideo() = coroutineScope {
        launch {

            val width = signage.width.toInt()/10
            val height = signage.height.toInt()/10
            val moduleWidth: Float =
                width.toFloat() / (signage.widthCabinetNumber * cabinet.moduleRowCount)
            val moduleHeight: Float =
                height.toFloat() / (signage.heightCabinetNumber * cabinet.moduleColCount)

            val videoPath = getPathFromUri(applicationContext, uri)
            if (videoPath == null) {
                Log.e("VideoProcess", "Failed to get file path from URI")
            }


            try {
                Log.d("VideoProcess", "1")
                val resultId = analysisViewModel.saveResult(signage.id)
                Log.d("VideoProcess", "2")
                Log.d("VideoProcess", "3")
                val videoCapture = VideoCapture()
                Log.d("VideoProcess", "4")
                var boo = videoCapture.open(videoPath, Videoio.CAP_ANDROID)
                Log.d("VideoProcess", boo.toString())

                val frameCount = videoCapture.get(Videoio.CAP_PROP_FRAME_COUNT).toInt()
                Log.d("VideoProcess", "${frameCount.toString()}")

                val frameCount2 = videoCapture.get(Videoio.CAP_PROP_POS_FRAMES).toInt()
                Log.d("VideoProcess", "${frameCount2.toString()}")

                if (videoCapture.isOpened) {
                    val frameSize = Size(
                        videoCapture.get(Videoio.CAP_PROP_FRAME_WIDTH),
                        videoCapture.get(Videoio.CAP_PROP_FRAME_HEIGHT)
                    )
                    Log.d("VideoProcess", "${frameSize.toString()}")
                    val frame = Mat(frameSize, CvType.CV_8UC3)
                    var points: MutableList<Point>? = null

                    for (i in 0 until frameCount) {
                        Log.d("VideoProcess", "${i.toString()}")

                        if (videoCapture.read(frame)) {
                            if (i == 0) {
                                points = getCorners(frame)
                            }
                            if (points != null && points.size == 4) {
                                val warpedMat = getWarp(frame, points, width, height)
                                val errorModuleList = getPredictions(
                                    warpedMat,
                                    errorDetectModule,
                                    width,
                                    height,
                                    moduleWidth,
                                    moduleHeight,
                                    resultId
                                )
                                for (errorModule in errorModuleList) {
                                    val processedMat: Mat = postProcess(
                                        warpedMat,
                                        errorModule,
                                        moduleWidth,
                                        moduleHeight
                                    )
                                    val processedBitmap =
                                        Bitmap.createBitmap(
                                            processedMat.cols(),
                                            processedMat.rows(),
                                            Bitmap.Config.ARGB_8888
                                        )
                                    Utils.matToBitmap(processedMat, processedBitmap)
                                    analysisViewModel.saveImage(processedBitmap, errorModule.id)
                                }
                            }
                            setProgresses(frameCount, i)
                        }
                    }
                } else {
                    Log.e("VideoProcessing", "Failed to open video file.")
                }

            } catch (e: Exception) {
                Log.e("VideoProcessing", "Error processing video file: ${e.localizedMessage}", e)
                finish()
            }


        }
    }

    private suspend fun detectPhoto() = coroutineScope {
        launch {
            Log.i("ImageProcess", uri.toString())
            val originalImage: Bitmap? = withContext(Dispatchers.IO) {
                loadBitmapFromUriWithGlide(
                    applicationContext,
                    uri
                )
            }
            if (originalImage != null) {
                Log.d(
                    "Image Size",
                    "${originalImage!!.toString()}"
                )
//            Log.d(
//                "Image Size",
//                "${originalImage!!.width.toString()}   ${originalImage!!.width.toString()}"
//            )

                val width = signage.width.toInt()/10
                val height = signage.height.toInt()/10
                val moduleWidth: Float =
                    width.toFloat() / (signage.widthCabinetNumber * cabinet.moduleRowCount)
                val moduleHeight: Float =
                    height.toFloat() / (signage.heightCabinetNumber * cabinet.moduleColCount)

                val resultId = analysisViewModel.saveResult(signage.id)
                Log.d("ImageProcess","resultId = ${resultId.toString()}")

                val originalMat: Mat = bitmapToMat(originalImage!!)
                try{
                    val points: MutableList<Point> = getCorners(originalMat)
                    Log.d("ImageProcess","resultId = ${points.toString()}")

                    val warpedMat = getWarp(originalMat, points, width, height)

                    val errorModuleList = getPredictions(
                        warpedMat,
                        errorDetectModule,
                        width,
                        height,
                        moduleWidth,
                        moduleHeight,
                        resultId
                    )
                    Log.d("ImageProcess","resultId = ${errorModuleList.toString()}")
                    for (errorModule in errorModuleList) {
                        val processedMat: Mat =
                            postProcess(warpedMat, errorModule, moduleWidth, moduleHeight)
                        val processedBitmap =
                            Bitmap.createBitmap(
                                processedMat.cols(),
                                processedMat.rows(),
                                Bitmap.Config.ARGB_8888
                            )
                        Utils.matToBitmap(processedMat, processedBitmap)
                        analysisViewModel.saveImage(processedBitmap, errorModule.id)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                Log.e("ImageProcess", "Image Load Fail!")
            }

        }
    }

    private fun postProcess(
        warpedMat: Mat,
        errorModule: ErrorModule,
        moduleWidth: Float,
        moduleHeight: Float
    ): Mat {
        var processedMat: Mat = warpedMat
        Imgproc.rectangle(
            processedMat,
            Point(errorModule.x.toDouble() * moduleWidth, errorModule.y.toDouble() * moduleHeight),
            Point(
                (errorModule.x + 1).toDouble() * moduleWidth,
                (errorModule.y + 1).toDouble() * moduleHeight
            ),
            Scalar(0.0, 0.0, 255.0),
            2
        )
        Imgproc.putText(
            processedMat,
            "%0.2f".format(errorModule.score),
            Point(
                errorModule.x.toDouble() * moduleWidth,
                (errorModule.y - 1).toDouble() * moduleHeight
            ),
            Imgproc.FONT_HERSHEY_PLAIN,
            20.0,
            Scalar(0.0, 0.0, 255.0),
            2
        )

        return processedMat
    }

    private suspend fun getPredictions(
        warpedMat: Mat,
        detectModule: Module,
        width: Int,
        height: Int,
        moduleWidth: Float,
        moduleHeight: Float,
        resultId: Long
    ): List<ErrorModule> {
        val imgScaleX = width.toFloat() / RESIZE_SIZE
        val imgScaleY = height.toFloat() / RESIZE_SIZE
        // 640x640 resize
        val resizedMat = Mat()
        Imgproc.resize(warpedMat, resizedMat, Size(RESIZE_SIZE.toDouble(), RESIZE_SIZE.toDouble()))
        // Mat to tensor
        val resizedBitmap =
            Bitmap.createBitmap(resizedMat.cols(), resizedMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resizedMat, resizedBitmap)
        val inputTensor: Tensor =
            TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, NO_MEAN_RGB, NO_STD_RGB)
        // detect
        val outputTuple = detectModule.forward(IValue.from(inputTensor)).toTuple()
        // output to error module entities
        val outputTensor: Tensor = outputTuple[0].toTensor()
        val outputFloats: FloatArray = outputTensor.dataAsFloatArray
        return outputFloatsToErrorModules(
            outputFloats,
            imgScaleX,
            imgScaleY,
            moduleWidth,
            moduleHeight,
            resultId
        )


    }

    private suspend fun outputFloatsToErrorModules(
        outputs: FloatArray,
        imgScaleX: Float,
        imgScaleY: Float,
        moduleWidth: Float,
        moduleHeight: Float,
        resultId: Long
    ): List<ErrorModule> {

        for (i in 0 until mOutputRow) {
            if (outputs[i * mOutputColumn + 4] > scoreThreshold) {
                val x = outputs[i * mOutputColumn]
                val y = outputs[i * mOutputColumn + 1]
                val w = outputs[i * mOutputColumn + 2]
                val h = outputs[i * mOutputColumn + 3]
                val score = outputs[i * mOutputColumn + 4]

                val left = imgScaleX * (x - w / 2)
                val top = imgScaleY * (y - h / 2)
                val right = imgScaleX * (x + w / 2)
                val bottom = imgScaleY * (y + h / 2)

                val centerX = (imgScaleX * x).toInt()
                val centerY = (imgScaleY * y).toInt()

                if (left % moduleWidth < rectThreshold && left % moduleWidth > moduleWidth - rectThreshold
                    && right % moduleWidth < rectThreshold && right % moduleWidth > moduleWidth - rectThreshold
                    && top % moduleHeight < rectThreshold && top % moduleHeight > moduleHeight - rectThreshold
                    && bottom % moduleHeight < rectThreshold && bottom % moduleHeight > moduleHeight - rectThreshold
                ) {
                    val moduleX: Int = (centerX / moduleWidth.toInt()) + 1
                    val moduleY: Int = (centerY / moduleHeight.toInt()) + 1
                    analysisViewModel.saveModule(
                        resultId = resultId,
                        score = score.toDouble(),
                        x = moduleX,
                        y = moduleY
                    )
                }
            }

        }
        return analysisViewModel.getRelatedModule(resultId)
    }


    private fun getCorners(originalMat: Mat): MutableList<Point> {
        var points = mutableListOf<Point>()

        val grayMat = Mat()
        Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_BGR2GRAY)
        val gauss = Mat()
        Imgproc.GaussianBlur(grayMat, gauss, Size(5.0, 5.0), 0.0)
        val edged = Mat()
        Imgproc.Canny(gauss, edged, 30.0, 80.0)

        val contours: MutableList<MatOfPoint> = ArrayList()
        val hierarchy = Mat()
        Imgproc.findContours(
            edged,
            contours,
            hierarchy,
            Imgproc.RETR_CCOMP,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        // contour 중 특정 크기 이상의 사각형인 contour를 찾습니다.
        val filteredContours = contours.filter { Imgproc.contourArea(it) > 10000 }

        // 근사치를 사용하여 다각형으로 변환합니다.
        val approxContours = filteredContours.map { contour ->
            val epsilon = 0.01 * Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, epsilon, true)
            MatOfPoint(*approx.toArray())
        }

        // 네 모서리를 가진 contour를 찾습니다.
        val quadContours = approxContours.filter { it.toList().size == 4 }

        // 면적이 가장 큰 contour를 찾습니다.
        val maxContour = quadContours.maxByOrNull { Imgproc.contourArea(it) }

        if (maxContour != null) {
            points = maxContour.toList()
        }


        if (points.size != 4) throw SignageNotFoundException("Signage Not Found")

        Log.i("Points", points.toString())
        return points
    }

    private fun getWarp(
        originalMat: Mat,
        points: MutableList<Point>,
        width: Int,
        height: Int
    ): Mat {
        val sortedPoints = sortPoints(points)
        val point: Array<Point> = sortedPoints.toTypedArray()
        val src = MatOfPoint2f(*point)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(width.toDouble(), 0.0),
            Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble())
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        // 원근 변환을 적용합니다.
        val result = Mat()
        Imgproc.warpPerspective(originalMat, result, m, Size(width.toDouble(), height.toDouble()))
        return result
    }

    private fun sortPoints(points: MutableList<Point>): MutableList<Point> {
        val a1 = points.minBy { it.x + it.y }
        val a2 = points.minBy { it.x - it.y }
        val a3 = points.maxBy { it.x + it.y }
        val a4 = points.maxBy { it.x - it.y }
        points.removeAll { true }
        points.add(a1)
        points.add(a2)
        points.add(a3)
        points.add(a4)
        return points
    }


    private fun setProgresses(frameCount: Int, i: Int) {

    }

    private fun loadBitmapFromUriWithGlide(context: Context, uri: Uri): Bitmap? = runBlocking {
        return@runBlocking try {
            Glide.with(context)
                .asBitmap()
                .load(uri)
                .submit()
                .get()
        } catch (e: Exception) {
            null
        }
    }

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    @Throws(IOException::class)
    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        context.assets.open(assetName).use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.flush()
            }
            return file.absolutePath
        }
    }

    fun getPathFromUri(context: Context, contentUri: Uri): String? {
        val cursor: Cursor? = context.contentResolver.query(contentUri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)
        if (cursor == null) {
            return contentUri.path
        } else {
            cursor.moveToFirst()
            val index: Int = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val path = cursor.getString(index)
            cursor.close()
            return path
        }
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_OK)
        super.onBackPressed()
    }
}