package com.nicko.airecorder.activities;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.nicko.airecorder.R;
import com.nicko.airecorder.databinding.ActivityAiAnalysisBinding;
import com.nicko.airecorder.utils.SystemBarsManager;
import com.nicko.airecorder.viewmodel.AiScreenState;
import com.nicko.airecorder.viewmodel.AiViewModel;

import org.json.JSONArray;
import org.json.JSONException;

public final class AiAnalysisActivity extends AppCompatActivity {
    public static final String EXTRA_RECORD_ID = "record_id";
    private ActivityAiAnalysisBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAiAnalysisBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBarsManager.apply(this, binding.getRoot());

        AiViewModel viewModel = new ViewModelProvider(this).get(AiViewModel.class);
        binding.btnBack.setOnClickListener(view -> finish());
        binding.btnStartAnalysis.setOnClickListener(view -> viewModel.startAnalysis());
        binding.btnCancelAnalysis.setOnClickListener(view -> viewModel.cancelAnalysis());
        viewModel.initialize(getIntent().getLongExtra(EXTRA_RECORD_ID, -1L));
        viewModel.getState().observe(this, this::render);
    }

    private void render(AiScreenState state) {
        binding.txtRecordTitle.setText(orDefault(state.getRecordTitle(), R.string.player_default_title));
        binding.txtAnalysisStatus.setText(statusText(state));
        binding.txtAnalysisHint.setText(hintText(state));
        binding.analysisProgress.setVisibility(state.isBusy()
                || state.getStatus() == AiScreenState.Status.LOADING ? View.VISIBLE : View.GONE);
        binding.btnStartAnalysis.setEnabled(state.canStart());
        binding.btnStartAnalysis.setVisibility(state.getStatus() == AiScreenState.Status.MISSING
                ? View.GONE : View.VISIBLE);
        binding.btnStartAnalysis.setText(state.hasResult()
                || state.getStatus() == AiScreenState.Status.FAILED
                || state.getStatus() == AiScreenState.Status.CANCELLED
                ? R.string.ai_retry : R.string.ai_start);
        binding.btnCancelAnalysis.setEnabled(state.canCancel());
        binding.btnCancelAnalysis.setVisibility(state.canCancel()
                || state.getOperation() == AiScreenState.Operation.CANCEL ? View.VISIBLE : View.GONE);
        String operationError = state.getOperationErrorCode();
        binding.txtOperationError.setVisibility(operationError == null ? View.GONE : View.VISIBLE);
        if (operationError != null) {
            binding.txtOperationError.setText(errorText(operationError));
        }

        binding.resultContainer.setVisibility(state.hasResult() ? View.VISIBLE : View.GONE);
        if (state.hasResult()) {
            binding.txtPreviousResult.setVisibility(state.getStatus() == AiScreenState.Status.SUCCEEDED
                    && !state.isBusy() ? View.GONE : View.VISIBLE);
            binding.txtSuggestedTitle.setText(orDefault(state.getSuggestedTitle(), R.string.ai_no_title));
            binding.txtSummary.setText(orDefault(state.getSummary(), R.string.ai_no_summary));
            binding.txtTranscript.setText(orDefault(state.getTranscript(), R.string.ai_no_transcript));
            binding.txtTasks.setText(tasksText(state.getTasksJson()));
        }
    }

    private int statusText(AiScreenState state) {
        if (state.getOperation() == AiScreenState.Operation.ENQUEUE) {
            return R.string.ai_status_enqueuing;
        }
        if (state.getOperation() == AiScreenState.Operation.CANCEL) {
            return R.string.ai_status_cancelling;
        }
        switch (state.getStatus()) {
            case EMPTY: return R.string.ai_status_empty;
            case PENDING: return R.string.ai_status_pending;
            case RUNNING: return R.string.ai_status_running;
            case SUCCEEDED: return R.string.ai_status_succeeded;
            case FAILED: return R.string.ai_status_failed;
            case CANCELLED: return R.string.ai_status_cancelled;
            case MISSING: return R.string.ai_status_missing;
            default: return R.string.ai_status_loading;
        }
    }

    private int hintText(AiScreenState state) {
        switch (state.getStatus()) {
            case EMPTY: return R.string.ai_hint_empty;
            case PENDING:
                return state.getErrorCode() == null ? R.string.ai_hint_pending : R.string.ai_hint_retry_pending;
            case RUNNING: return R.string.ai_hint_running;
            case SUCCEEDED: return R.string.ai_hint_succeeded;
            case FAILED: return errorText(state.getErrorCode());
            case CANCELLED: return R.string.ai_hint_cancelled;
            case MISSING: return R.string.ai_hint_missing;
            default: return R.string.ai_hint_loading;
        }
    }

    private int errorText(@Nullable String code) {
        if (code == null) {
            return R.string.ai_error_processing;
        }
        switch (code) {
            case "AI_NOT_CONFIGURED": return R.string.ai_error_not_configured;
            case "INVALID_RECORDING":
            case "INVALID_RECORD_ID":
            case "RECORD_NOT_FOUND":
            case "UNSUPPORTED_FORMAT": return R.string.ai_error_recording;
            case "NETWORK_UNAVAILABLE":
            case "AI_UNAVAILABLE":
            case "AI_SERVER_ERROR": return R.string.ai_error_network;
            case "SERVER_BUSY": return R.string.ai_error_server_busy;
            case "AI_TIMEOUT":
            case "UPLOAD_TIMEOUT": return R.string.ai_error_timeout;
            case "FILE_TOO_LARGE": return R.string.ai_error_file_too_large;
            case "AUDIO_TOO_LONG":
            case "TEXT_TOO_LONG": return R.string.ai_error_note_too_long;
            case "NO_SPEECH": return R.string.ai_no_transcript;
            case "AI_MODEL_UNAVAILABLE": return R.string.ai_error_model_unavailable;
            case "AI_ENDPOINT_NOT_FOUND": return R.string.ai_error_endpoint;
            case "AI_AUTH_REQUIRED": return R.string.ai_error_access;
            case "INVALID_AI_RESPONSE":
            case "INVALID_AI_RESULT":
            case "AI_RESPONSE_INVALID":
            case "AI_TASK_UNGROUNDED": return R.string.ai_error_response;
            case "RETRY_LIMIT_REACHED": return R.string.ai_error_retry_limit;
            case "ANALYSIS_INTERRUPTED": return R.string.ai_error_interrupted;
            case "QUEUE_FAILED": return R.string.ai_error_queue;
            case "STORAGE_ERROR": return R.string.ai_error_storage;
            default: return R.string.ai_error_processing;
        }
    }

    private CharSequence orDefault(@Nullable String text, int fallback) {
        return text == null || text.trim().isEmpty() ? getString(fallback) : text;
    }

    private CharSequence tasksText(@Nullable String json) {
        if (json == null) {
            return getString(R.string.ai_no_tasks);
        }
        try {
            JSONArray array = new JSONArray(json);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                Object value = array.get(i);
                if (!(value instanceof String)) {
                    return getString(R.string.ai_error_tasks);
                }
                String task = ((String) value).trim();
                if (!task.isEmpty()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append("• ").append(task);
                }
            }
            return text.length() == 0 ? getString(R.string.ai_no_tasks) : text.toString();
        } catch (JSONException error) {
            return getString(R.string.ai_error_tasks);
        }
    }
}
