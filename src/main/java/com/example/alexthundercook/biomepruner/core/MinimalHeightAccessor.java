package com.example.alexthundercook.biomepruner.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Minimal height accessor for use during early world generation
 * when we don't have access to actual chunks yet
 */
public class MinimalHeightAccessor implements LevelHeightAccessor {

    // Standard overworld height values
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    @Override
    public int getMinBuildHeight() {
        return MIN_Y;
    }

    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    public int getMaxY() {
        return getMinY() + getHeight();
    }

    @Override
    public int getSectionsCount() {
        return (getHeight() >> 4) + (getHeight() % 16 == 0 ? 0 : 1);
    }

    public int getMinSectionY() {
        return getMinY() >> 4;
    }

    public int getMaxSectionY() {
        return ((getMinY() + getHeight() - 1) >> 4) + 1;
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return isOutsideBuildHeight(pos.getY());
    }

    @Override
    public boolean isOutsideBuildHeight(int y) {
        return y < getMinY() || y >= getMaxY();
    }

    @Override
    public int getSectionIndex(int y) {
        return (y >> 4) - getMinSectionY();
    }

    @Override
    public int getSectionIndexFromSectionY(int sectionY) {
        return sectionY - getMinSectionY();
    }

    @Override
    public int getSectionYFromSectionIndex(int sectionIndex) {
        return sectionIndex + getMinSectionY();
    }
}