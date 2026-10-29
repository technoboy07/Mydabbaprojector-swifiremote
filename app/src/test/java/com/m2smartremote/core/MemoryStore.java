package com.m2smartremote.core;

import java.util.ArrayList;
import java.util.List;

final class MemoryStore implements SessionStore {
    List<Record> records = new ArrayList<>();
    int saves;

    @Override
    public List<Record> load() {
        return new ArrayList<>(records);
    }

    @Override
    public void save(List<Record> r) {
        records = new ArrayList<>(r);
        saves++;
    }
}
