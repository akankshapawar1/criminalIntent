package com.bignerdranch.android.criminalintent
//This is my face detection class `package com.bignerdranch.android.criminalintent.mlutils

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

class FaceDetectionAndC {

    private val TAG = "FACE_DETECT_TAG"
    private var numDetectedFaces: Int = 0

    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        detector = FaceDetection.getClient(options)
        Log.v(TAG, "Face detector options: $options")
    }

    fun analyzePhoto(bitmap: Bitmap) : Int {
        Log.d(TAG, "analyzing the photo: ")

        // input Image for analyzing
        val inputImage = InputImage.fromBitmap(bitmap, 270)

        // start detecting
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                // Task completed
                Log.d(TAG, "analyzePhoto: Successfully detected face...")
                getRectangles(bitmap, faces)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "analyzePhoto: ", e)
            }
        return this.numDetectedFaces
    }

    private fun getRectangles(bitmap: Bitmap, faces: List<Face>) {
        // TODO: To overlay rectangles for face detections
        Log.d(TAG, "Start drawing rectangles for detected faces $faces")
        Log.d(TAG, "The number of detected faces ${faces.size}")

        val canvas = Canvas(bitmap)

        for (face in faces) {
            val boundingBox = face.boundingBox
            val paint = Paint()
            paint.color = Color.rgb(116, 40, 161)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5.0f
            canvas.drawRect(boundingBox, paint)

            val contourPaint  = Paint()
            contourPaint.setColor(Color.RED);
            contourPaint.setStyle(Paint.Style.STROKE);
            contourPaint.setStrokeWidth(7.0f);



        }

        Log.d(TAG, "Completed drawing faces ...")

        numDetectedFaces = faces.size

        Log.d("TAG", "numDetectedFaces = $numDetectedFaces")
    }

    fun returnNumFaces() : Int {
        Log.d("TAG", "num faces within returnNumFaces() ${this.numDetectedFaces}")
        return this.numDetectedFaces
    }

    fun stop() {
        detector.close()
    }
}