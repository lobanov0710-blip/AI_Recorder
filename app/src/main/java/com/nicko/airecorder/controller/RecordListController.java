package com.nicko.airecorder.controller;

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

    public RecordListController(
            RecordViewModel viewModel,
            RecordAdapter adapter
    ) {

        this.viewModel = viewModel;
        this.adapter = adapter;

    }

    public void observe(LifecycleOwner owner) {

        viewModel.getRecords().observe(owner, list -> {

            adapter.submitList(

                    convertToItems(list)

            );

        });

    }
    private List<RecordItem> convertToItems(List<RecordEntity> entities) {

        List<RecordItem> items = new ArrayList<>();

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