package com.nicko.airecorder.controller;

import android.view.View;

import androidx.lifecycle.LifecycleOwner;

import com.nicko.airecorder.adapter.RecordAdapter;
import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.model.RecordItem;
import com.nicko.airecorder.viewmodel.RecordViewModel;

import java.util.ArrayList;
import java.util.List;

public class RecordListController {

    private final RecordViewModel viewModel;

    private final RecordAdapter adapter;

    private final View emptyState;

    public RecordListController(
            RecordViewModel viewModel,
            RecordAdapter adapter,
            View emptyState
    ) {

        this.viewModel = viewModel;

        this.adapter = adapter;

        this.emptyState = emptyState;
    }

    public void observe(
            LifecycleOwner owner
    ) {

        viewModel
                .getRecords()
                .observe(
                        owner,
                        list -> {

                            List<RecordItem> items =
                                    convertToItems(
                                            list
                                    );

                            adapter.submitList(
                                    items
                            );

                            updateEmptyState(
                                    items.isEmpty()
                            );
                        }
                );
    }

    private void updateEmptyState(
            boolean isEmpty
    ) {

        if (emptyState == null) {
            return;
        }

        emptyState.setVisibility(
                isEmpty
                        ? View.VISIBLE
                        : View.GONE
        );
    }

    private List<RecordItem> convertToItems(
            List<RecordEntity> entities
    ) {

        List<RecordItem> items =
                new ArrayList<>();

        if (entities == null) {
            return items;
        }

        for (RecordEntity entity : entities) {

            items.add(

                    new RecordItem(

                            entity.getId(),

                            entity.getFileName(),

                            entity.getFilePath(),

                            entity.getCreatedAt(),

                            entity.getTitle(),

                            entity.getDuration()
                    )
            );
        }

        return items;
    }
}