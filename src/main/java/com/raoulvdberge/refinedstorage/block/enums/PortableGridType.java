package com.raoulvdberge.refinedstorage.block.enums;

import net.minecraft.util.IStringSerializable;

public enum PortableGridType implements IStringSerializable {
    NORMAL(0, "normal"),
    CREATIVE(1, "creative");

    private int id;
    private String name;

    PortableGridType(int id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    public int getId() {
        return id;
    }

    @Override
    public String toString() {
        return name;
    }
}
