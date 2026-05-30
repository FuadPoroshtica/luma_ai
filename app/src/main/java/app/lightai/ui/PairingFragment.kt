package app.lightai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.lightai.R
import app.lightai.data.GatewaySetupCodeDecoder
import app.lightai.data.SecurePrefs
import app.lightai.databinding.FragmentPairingBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PairingFragment : Fragment() {
    private var _binding: FragmentPairingBinding? = null
    private val binding get() = _binding!!

    private lateinit var cameraExecutor: ExecutorService
    private var paired = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.pairingStatus.setText(R.string.pairing_status_no_camera)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.pairingManualButton.setOnClickListener {
            showManualEntryDialog()
        }

        ensureCameraPermission()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val context = requireContext()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview =
                Preview.Builder().build().apply {
                    surfaceProvider = binding.pairingPreview.surfaceProvider
                }

            val analyzer =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(cameraExecutor, QrAnalyzer { payload -> onQrDetected(payload) })
                    }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                binding.pairingStatus.setText(R.string.pairing_status_no_camera)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun onQrDetected(rawPayload: String) {
        if (paired) return
        val setupCode = GatewaySetupCodeDecoder.resolveScannedPayload(rawPayload) ?: return
        val cfg = GatewaySetupCodeDecoder.resolveConnectConfig(setupCode) ?: run {
            requireActivity().runOnUiThread {
                binding.pairingStatus.text = getString(R.string.pairing_status_invalid)
            }
            return
        }
        paired = true
        SecurePrefs.getInstance(requireContext()).gatewaySetupCode = setupCode
        requireActivity().runOnUiThread {
            binding.pairingStatus.text = getString(R.string.pairing_status_paired, cfg.host)
            Toast.makeText(requireContext(), R.string.pairing_success, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }

    private fun showManualEntryDialog() {
        val context = requireContext()
        val edit =
            android.widget.EditText(context).apply {
                hint = getString(R.string.pairing_manual_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setSingleLine(false)
                maxLines = 4
                // Prefill from clipboard if the user just copied a code
                val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager?
                cb?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let { clip ->
                    val trimmed = clip.trim()
                    if (trimmed.length in 16..512 && trimmed.all { it == '-' || it == '_' || it.isLetterOrDigit() || it == '=' || it == '{' || it == '}' || it == '"' || it == ':' || it == ',' }) {
                        setText(trimmed)
                    }
                }
                setPadding(48, 32, 48, 32)
            }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.pairing_manual_title)
            .setMessage(R.string.pairing_manual_help)
            .setView(edit)
            .setPositiveButton(R.string.pairing_manual_submit) { _, _ ->
                onManualCode(edit.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onManualCode(raw: String) {
        val setupCode = GatewaySetupCodeDecoder.resolveScannedPayload(raw)
        if (setupCode == null) {
            Toast.makeText(requireContext(), R.string.pairing_status_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val cfg = GatewaySetupCodeDecoder.resolveConnectConfig(setupCode)
        if (cfg == null) {
            Toast.makeText(requireContext(), R.string.pairing_status_invalid, Toast.LENGTH_LONG).show()
            return
        }
        SecurePrefs.getInstance(requireContext()).gatewaySetupCode = setupCode
        binding.pairingStatus.text = getString(R.string.pairing_status_paired, cfg.host)
        Toast.makeText(requireContext(), R.string.pairing_success, Toast.LENGTH_SHORT).show()
        findNavController().popBackStack()
    }

    private class QrAnalyzer(
        private val onDetected: (String) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private val scanner =
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build(),
            )

        @ExperimentalGetImage
        override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
            val media = imageProxy.image
            if (media == null) {
                imageProxy.close()
                return
            }
            val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstOrNull()?.rawValue?.let(onDetected)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    companion object {
        private const val TAG = "LightAI-Pairing"
    }
}
