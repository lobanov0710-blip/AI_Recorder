package com.nicko.airecorder.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.nicko.airecorder.R;
import com.nicko.airecorder.model.RecordItem;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordAdapter
        extends ListAdapter<RecordItem, RecordAdapter.ViewHolder> {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat(
                    "dd.MM.yyyy HH:mm",
                    Locale.getDefault()
            );

    public interface OnItemClickListener {

        void onItemClick(RecordItem item);

        void onItemLongClick(RecordItem item);

        void onDeleteClick(RecordItem item);

        void onShareClick(RecordItem item);
    }

    private static final DiffUtil.ItemCallback<RecordItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<RecordItem>() {

                @Override
                public boolean areItemsTheSame(
                        @NonNull RecordItem oldItem,
                        @NonNull RecordItem newItem) {

                    return oldItem.getId() == newItem.getId();
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull RecordItem oldItem,
                        @NonNull RecordItem newItem) {

                    return oldItem.equals(newItem);
                }
            };

    private final OnItemClickListener listener;

    public RecordAdapter(OnItemClickListener listener) {

        super(DIFF_CALLBACK);

        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType) {

        View view = LayoutInflater.from(parent.getContext())
                .inflate(
                        R.layout.item_record,
                        parent,
                        false
                );

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
            @NonNull ViewHolder holder,
            int position
    ) {

        RecordItem item = getItem(position);

        holder.txtFileName.setText(getDisplayTitle(item));

        holder.txtDate.setText(
                DATE_FORMAT.format(
                        new Date(item.getCreatedAt())
                )
        );

        long seconds = item.getDuration() / 1000;

        long minutes = seconds / 60;

        seconds %= 60;

        holder.txtDuration.setText(

                String.format(

                        Locale.getDefault(),

                        "%02d:%02d",

                        minutes,

                        seconds

                )

        );

        holder.itemView.setOnClickListener(v ->
                listener.onItemClick(item));

        holder.itemView.setOnLongClickListener(v -> {

            listener.onItemLongClick(item);

            return true;

        });

        holder.btnDelete.setOnClickListener(v ->
                listener.onDeleteClick(item));

        holder.btnShare.setOnClickListener(v ->
                listener.onShareClick(item));

    }
    private String getDisplayTitle(RecordItem item) {

        String title = item.getTitle();

        if (title == null || title.trim().isEmpty()) {

            return item.getFileName();

        }

        return title;

    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        final TextView txtFileName;

        final TextView txtDate;

        final TextView txtDuration;

        final Button btnDelete;

        final Button btnShare;

        ViewHolder(@NonNull View itemView) {

            super(itemView);

            txtFileName =
                    itemView.findViewById(R.id.txtFileName);

            txtDate =
                    itemView.findViewById(R.id.txtDate);

            txtDuration =
                    itemView.findViewById(R.id.txtDuration);

            btnDelete =
                    itemView.findViewById(R.id.btnDelete);

            btnShare =
                    itemView.findViewById(R.id.btnShare);
        }
    }
}