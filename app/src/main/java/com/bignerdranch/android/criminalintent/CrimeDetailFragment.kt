package com.bignerdranch.android.criminalintent

import android.content.Intent
import com.bignerdranch.android.criminalintent.FaceDetectionAndC
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bignerdranch.android.criminalintent.databinding.FragmentCrimeDetailBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import androidx.room.util.FileUtil.copy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.common.PointF3D
import com.google.mlkit.vision.common.Triangle
import com.google.mlkit.vision.face.*
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions.UseCase
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint


private const val DATE_FORMAT = "EEE, MMM, dd"

class CrimeDetailFragment : Fragment() {

    private var _binding: FragmentCrimeDetailBinding? = null
    private val binding
        get() = checkNotNull(_binding) {
            "Cannot access binding because it is null. Is the view visible?"
        }

    private val args: CrimeDetailFragmentArgs by navArgs()

    private val crimeDetailViewModel: CrimeDetailViewModel by viewModels {
        CrimeDetailViewModelFactory(args.crimeId)
    }

    private val selectSuspect = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        uri?.let { parseContactSelection(it) }
    }

    private var photoName: String? = null

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { didTakePhoto: Boolean ->
        if (didTakePhoto && photoName != null) {
            crimeDetailViewModel.updateCrime { oldCrime ->
                oldCrime.copy(photoFileName = photoName)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding =
            FragmentCrimeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            crimeTitle.doOnTextChanged { text, _, _, _ ->
                crimeDetailViewModel.updateCrime { oldCrime ->
                    oldCrime.copy(title = text.toString())
                }
            }

            crimeSolved.setOnCheckedChangeListener { _, isChecked ->
                crimeDetailViewModel.updateCrime { oldCrime ->
                    oldCrime.copy(isSolved = isChecked)
                }
            }

            crimeSuspect.setOnClickListener {
                selectSuspect.launch(null)
            }

            val selectSuspectIntent = selectSuspect.contract.createIntent(
                requireContext(),
                null
            )
            crimeSuspect.isEnabled = canResolveIntent(selectSuspectIntent)

            crimeCamera.setOnClickListener {
                photoName = "IMG_${Date()}.JPG"
                val photoFile = File(
                    requireContext().applicationContext.filesDir,
                    photoName
                )
                val photoUri = FileProvider.getUriForFile(
                    requireContext(),
                    "com.bignerdranch.android.criminalintent.fileprovider",
                    photoFile
                )

                takePhoto.launch(photoUri)
            }

            val captureImageIntent = takePhoto.contract.createIntent(
                requireContext(),
                null
            )
            crimeCamera.isEnabled = canResolveIntent(captureImageIntent)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                crimeDetailViewModel.crime.collect { crime ->
                    crime?.let { updateUi(it) }
                }
            }
        }

        setFragmentResultListener(
            DatePickerFragment.REQUEST_KEY_DATE
        ) { _, bundle ->
            val newDate =
                bundle.getSerializable(DatePickerFragment.BUNDLE_KEY_DATE) as Date
            crimeDetailViewModel.updateCrime { it.copy(date = newDate) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateUi(crime: Crime) {
        binding.apply {
            if (crimeTitle.text.toString() != crime.title) {
                crimeTitle.setText(crime.title)
            }
            crimeDate.text = crime.date.toString()
            crimeDate.setOnClickListener {
                findNavController().navigate(
                    CrimeDetailFragmentDirections.selectDate(crime.date)
                )
            }

            crimeSolved.isChecked = crime.isSolved

            crimeReport.setOnClickListener {
                val reportIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getCrimeReport(crime))
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        getString(R.string.crime_report_subject)
                    )
                }
                val chooserIntent = Intent.createChooser(
                    reportIntent,
                    getString(R.string.send_report)
                )
                startActivity(chooserIntent)
            }

            crimeSuspect.text = crime.suspect.ifEmpty {
                getString(R.string.crime_suspect_text)
            }

            updatePhoto(crime.photoFileName)
        }
    }

    private fun getCrimeReport(crime: Crime): String {
        val solvedString = if (crime.isSolved) {
            getString(R.string.crime_report_solved)
        } else {
            getString(R.string.crime_report_unsolved)
        }

        val dateString = DateFormat.format(DATE_FORMAT, crime.date).toString()
        val suspectText = if (crime.suspect.isBlank()) {
            getString(R.string.crime_report_no_suspect)
        } else {
            getString(R.string.crime_report_suspect, crime.suspect)
        }

        return getString(
            R.string.crime_report,
            crime.title, dateString, solvedString, suspectText
        )
    }

    private fun parseContactSelection(contactUri: Uri) {
        val queryFields = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)

        val queryCursor = requireActivity().contentResolver
            .query(contactUri, queryFields, null, null, null)

        queryCursor?.use { cursor ->
            if (cursor.moveToFirst()) {
                val suspect = cursor.getString(0)
                crimeDetailViewModel.updateCrime { oldCrime ->
                    oldCrime.copy(suspect = suspect)
                }
            }
        }
    }

    private fun canResolveIntent(intent: Intent): Boolean {
        val packageManager: PackageManager = requireActivity().packageManager
        val resolvedActivity: ResolveInfo? =
            packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
        return resolvedActivity != null
    }

    private fun updatePhoto(photoFileName: String?) {
        if (binding.crimePhoto.tag != photoFileName) {
            val photoFile = photoFileName?.let {
                File(requireContext().applicationContext.filesDir, it)
            }

            if (photoFile?.exists() == true) {

                binding.crimePhoto.doOnLayout { measuredView ->
                    val scaledBitmap = getScaledBitmap(
                        photoFile.path,
                        measuredView.width,
                        measuredView.height
                    )
                    binding.crimePhoto.setImageBitmap(scaledBitmap)
                    binding.crimePhoto.tag = photoFileName
                    binding.crimePhoto.contentDescription =
                        getString(R.string.crime_photo_image_description)


                    //Works with only selfies due to rotation issue
                    //Not sure what is happening with countouring
                    val photoBitMap = BitmapFactory.decodeFile(photoFile.toString())
                    //var detector: FaceDetector? = null

                    //face detection + contour
                    val options = FaceDetectorOptions.Builder()
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .build()

                    val detector = FaceDetection.getClient(options)

                    val image = InputImage.fromBitmap(photoBitMap!!, 0)

                    /*
                    detector = FaceDetection.getClient(
                        FaceDetectorOptions.Builder()
                            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                            .build())

                    //face detection

                    val result = detector.process(image)
                        .addOnSuccessListener { faces ->
                            var faceCount = 0
                            for (face in faces){
                                faceCount +=1
                            }
                            Log.i("Face count",faceCount.toString())
                            println("LINE308:CrimeDetailFrag Detector Success!\n Value of faces: $faces")
                            photoBitMap.apply{
                                binding.crimePhoto.setImageBitmap(drawWithRectangle(faces))
                                binding.crimePhoto.setImageBitmap(drawContour(faces))
                            }
                    }
                        .addOnFailureListener{e->
                            Log.i("Face detection exception",e.toString())
                        }
                    Log.i("faceDetectorResult", "$result")
                */

                    //mesh detection
                    var meshDetector = FaceMeshDetection.getClient(
                        FaceMeshDetectorOptions.Builder()
                            .setUseCase(1)
                            .build()
                    )

                    val meshResult = meshDetector.process(image)
                        .addOnSuccessListener { result ->
                            // Task completed successfully
                            Log.i("L326", "in mesh detector")
                            for (faceMesh in result) {
                                val bounds: Rect = faceMesh.boundingBox

                                /*
                                // Gets all points
                                val faceMeshpoints = faceMesh.allPoints
                                for (faceMeshpoint in faceMeshpoints) {
                                    val index: Int = faceMeshpoints.index()
                                    val position = faceMeshpoint.position
                                }
                             */

                                // Gets triangle info
                                val triangles: List<Triangle<FaceMeshPoint>> = faceMesh.allTriangles
                                for (triangle in triangles) {
                                    /*
                                    //3 Points connecting to each other and representing a triangle area.
                                    val connectedPoints = triangle.allPoints
                                    Log.d("connected points", "$connectedPoints")
                                    //printing the following msg (not able to see actual points)
                                    //[com.google.mlkit.vision.facemesh.FaceMeshPoint@14cc052,
                                    //com.google.mlkit.vision.facemesh.FaceMeshPoint@1b30f23,
                                    //com.google.mlkit.vision.facemesh.FaceMeshPoint@fec4c20]
                                     */
                                    val point1 = triangle.allPoints[0].position
                                    val point2 = triangle.allPoints[1].position
                                    val point3 = triangle.allPoints[2].position
                                    //1 connected points     PointF3D{x=1233.7478, y=1151.3823, z=-193.97575}
                                    //2 connected points     PointF3D{x=1236.8816, y=1117.7338, z=-177.6444}
                                    //3 connected points     PointF3D{x=1190.9344, y=1145.2256, z=-236.19054}
                                    Log.d("1 connected points", "$point1")
                                    Log.d("2 connected points", "$point2")
                                    Log.d("3 connected points", "$point3")

                                    photoBitMap.apply {
                                        binding.crimePhoto.setImageBitmap(drawTriangle(point1,point2,point3))
                                    }

                                }
                            }

                        }
                        .addOnFailureListener { e ->
                            // Task failed with an exception
                            Log.i("Face mesh detection exception", e.toString())
                        }

                }
            } else {
                binding.crimePhoto.setImageBitmap(null)
                binding.crimePhoto.tag = null
                binding.crimePhoto.contentDescription =
                    getString(R.string.crime_photo_no_image_description)
            }
        }

        fun Bitmap.drawWithRectangle(faces: List<Face>): Bitmap? {

            println("LINE374:Inside the drawWithRectangles..")

            // MAKE A COPY OF THE BITMAP USED THE CALL THE FUNCTION
            val bitmap = copy(config, true)
            // INITIALIZE A CANVAS WITH THE BITMAP
            val canvas = Canvas(bitmap)
            //FOR EACH FACE IN THE LIST OF FACES, CALL THE BOUNDINGBOX PROPERTY
            for (face in faces) {
                println("LINE382: Iterating through faces, current value of face in faces: $face")
                val bounds = face.boundingBox
                // CALL A PAINT OBJECT, DEFINE A RECTANGLE WITH ITS APPLY METHOD
                Paint().apply {
                    color = Color.RED
                    style = Paint.Style.STROKE
                    strokeWidth = 4.0f
                    isAntiAlias = true
                    // USE THE CANVAS DRAWRECT METHOD TO DRAW THE RECTANGLE
                    // THE CANVAS WAS INITILIZED WITH THE BITMAP SO THE RECTANGLES
                    // SHOULD BE DRAWN ON THE BITMAP.
                    canvas.drawRect(
                        bounds,
                        this
                    )
                }
            }
            return bitmap
        }

        fun Bitmap.drawContour(faces: List<Face>): Bitmap? {
            val bitmap = copy(config, true)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.color = Color.BLUE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6F

            faces.forEach { face ->
                face.getContour(FaceContour.LEFT_EYE)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.RIGHT_EYE)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.LEFT_EYEBROW_BOTTOM)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.RIGHT_EYEBROW_BOTTOM)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.NOSE_BRIDGE)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.NOSE_BOTTOM)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.LOWER_LIP_TOP)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.UPPER_LIP_TOP)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }
                face.getContour(FaceContour.FACE)?.let { contour ->
                    drawContourFun(canvas, paint, contour)
                }

            }
            return bitmap
        }
    }

    private fun Bitmap.drawTriangle(point1: PointF3D?, point2: PointF3D?, point3: PointF3D?): Bitmap? {
        val bitmap = copy(config, true)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.BLUE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6F

        val p1x = point1?.x
        val p1y = point1?.y
        val p2x = point2?.x
        val p2y = point2?.y

        /*
        val matrix = Matrix()

        val p1array = point1?.let { floatArrayOf(point1.x, point1.y, point1.z) }
        val p2array = point2?.let { floatArrayOf(point2.x, point2.y, point2.z) }
        val p3array = point3?.let { floatArrayOf(point3.x, point3.y, point3.z) }

        matrix.mapPoints(p1array)
        matrix.mapPoints(p2array)
        matrix.mapPoints(p3array)

        canvas.drawBitmap(bitmap,matrix,paint)
         */

        /*if (point1 != null && point2 != null) {
            canvas.drawLine(point1.x,point1.y, point2.x, point2.y, paint)
            Log.d("drawingLines", "$point1.x")
        }
         */

        Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4.0f
            isAntiAlias = true
            // USE THE CANVAS DRAWRECT METHOD TO DRAW THE RECTANGLE
            // THE CANVAS WAS INITILIZED WITH THE BITMAP SO THE RECTANGLES
            // SHOULD BE DRAWN ON THE BITMAP.
            //if (point1 != null && point2 != null) {
                if (p1x != null && p1y != null && p2x != null && p2y != null) {
                    canvas.drawLine(p1x, p1y, p2x, p2y, paint)
                }
                Log.d("drawingLines", "$p1x")
            }
        //}
        return bitmap
    }

    fun drawContourFun(canvas: Canvas, paint: Paint, contour: FaceContour) {
        val path = Path()
        contour.points.forEachIndexed { index, point ->
            val position = PointF(point.x, point.y)
            if (index == 0) {
                path.moveTo(position.x, position.y)
            } else {
                path.lineTo(position.x, position.y)
            }
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}