/**
 * This file is part of AlmuraSDK, All Rights Reserved.
 *
 * Copyright (c) 2015 AlmuraDev <http://github.com/AlmuraDev/>
 */
package com.almuradev.almurasdk.core;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

public class AlmuraSDKCoreMod implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return CommonAccessTransformer.CLASSPATH;
    }
}