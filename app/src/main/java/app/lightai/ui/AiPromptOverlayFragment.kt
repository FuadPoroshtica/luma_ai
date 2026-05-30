package app.lightai.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.lightai.R
import app.lightai.databinding.FragmentAiPromptBinding
import kotlinx.coroutines.launch

class AiPromptOverlayFragment : Fragment() {
    private var _binding: FragmentAiPromptBinding? = null
    private val binding get() = _binding!!

    private val mode: String by lazy { arguments?.getString(ARG_MODE) ?: MODE_TEXT }

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                matches?.firstOrNull()?.let { transcript ->
                    _binding?.aiPromptInput?.apply {
                        setText(transcript)
                        setSelection(text?.length ?: 0)
                        requestFocus()
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAiPromptBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.aiPromptInput.apply {
            requestFocus()
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit()
                    true
                } else {
                    false
                }
            }
        }

        binding.aiPromptSendButton.setOnClickListener { submit() }
        binding.aiPromptVoiceButton.setOnClickListener { launchVoice() }
        binding.aiPromptCancelButton.setOnClickListener { dismiss() }

        // Tapping outside the card dismisses the overlay.
        binding.aiPromptOverlayRoot.setOnClickListener { dismiss() }
        // Prevent root click from firing when the card itself is touched.
        binding.aiPromptCard.setOnClickListener { /* swallow */ }

        showKeyboard()

        if (savedInstanceState == null && mode == MODE_VOICE) {
            launchVoice()
        }
    }

    override fun onDestroyView() {
        hideKeyboard()
        super.onDestroyView()
        _binding = null
    }

    private var activeRunId: String? = null
    private val chatBuffer = StringBuilder()

    private fun submit() {
        val prompt = binding.aiPromptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return

        val context = requireContext()
        val gateway = app.lightai.helper.GatewayClient.shared()
        if (gateway.status.value == app.lightai.helper.GatewayClient.Status.Connected) {
            sendViaGateway(prompt)
            return
        }

        val dispatched =
            sendToClaude(context, prompt) ||
                sendToOpenClaw(context, prompt) ||
                sendToAssistant(context, prompt)

        if (dispatched) {
            hideKeyboard()
            findNavController().popBackStack()
        } else {
            Toast.makeText(context, R.string.ai_prompt_no_target, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendViaGateway(prompt: String) {
        val gateway = app.lightai.helper.GatewayClient.shared()
        val runId = gateway.sendChat(prompt) ?: return
        activeRunId = runId
        chatBuffer.clear()

        // Switch overlay to streaming-response mode.
        hideKeyboard()
        binding.aiPromptInput.visibility = android.view.View.GONE
        binding.aiPromptResponse.visibility = android.view.View.VISIBLE
        binding.aiPromptResponse.text = getString(R.string.ai_prompt_thinking)
        binding.aiPromptVoiceButton.visibility = android.view.View.GONE
        binding.aiPromptSendButton.visibility = android.view.View.GONE
        binding.aiPromptCancelButton.text = getString(R.string.app_drawer_cancel)

        viewLifecycleOwner.lifecycleScope.launch {
            gateway.chatStream.collect { chunk ->
                if (chunk.runId != activeRunId) return@collect
                when (chunk.state) {
                    "delta" -> {
                        chatBuffer.append(chunk.text)
                        binding.aiPromptResponse.text = chatBuffer.toString()
                    }
                    "final" -> {
                        if (chunk.text.isNotEmpty()) {
                            binding.aiPromptResponse.text = chunk.text
                        }
                        binding.aiPromptCancelButton.text = getString(R.string.app_drawer_cancel)
                    }
                    "error", "aborted" -> {
                        binding.aiPromptResponse.text =
                            (if (chatBuffer.isEmpty()) "" else chatBuffer.toString() + "\n\n") +
                                "[${chunk.state}]" +
                                if (chunk.text.isNotEmpty()) " ${chunk.text}" else ""
                    }
                }
            }
        }
    }

    private fun sendToClaude(
        context: Context,
        prompt: String,
    ): Boolean {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(CLAUDE_PACKAGE)
                putExtra(Intent.EXTRA_TEXT, prompt)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun sendToOpenClaw(
        context: Context,
        prompt: String,
    ): Boolean {
        // OpenClaw exposes a custom intent action that takes a `prompt` extra
        // directly — defined in their res/xml/shortcuts.xml capability.
        // This is the cleanest deep-link path; the prompt lands pre-filled in chat.
        val intent =
            Intent(OPENCLAW_ASK_ACTION).apply {
                setClassName(OPENCLAW_PACKAGE, "$OPENCLAW_PACKAGE.MainActivity")
                putExtra("prompt", prompt)
                putExtra(Intent.EXTRA_TEXT, prompt)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun sendToAssistant(
        context: Context,
        prompt: String,
    ): Boolean {
        val hintBundle =
            Bundle().apply {
                putString(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putString("android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD", prompt)
            }
        val intent =
            Intent(Intent.ACTION_ASSIST).apply {
                putExtra(Intent.EXTRA_TEXT, prompt)
                putExtra("android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD", prompt)
                putExtra(Intent.EXTRA_ASSIST_CONTEXT, hintBundle)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun launchVoice() {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ai_prompt_voice_hint))
            }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.ai_prompt_voice_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismiss() {
        hideKeyboard()
        findNavController().popBackStack()
    }

    private fun showKeyboard() {
        val context = context ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        binding.aiPromptInput.post {
            val view = _binding?.aiPromptInput ?: return@post
            view.requestFocus()
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val context = context ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = view?.windowToken ?: return
        imm?.hideSoftInputFromWindow(token, 0)
    }

    companion object {
        const val ARG_MODE = "mode"
        const val MODE_TEXT = "text"
        const val MODE_VOICE = "voice"
        private const val CLAUDE_PACKAGE = "com.anthropic.claude"
        private const val OPENCLAW_PACKAGE = "ai.openclaw.app"
        private const val OPENCLAW_ASK_ACTION = "ai.openclaw.app.action.ASK_OPENCLAW"
    }
}
