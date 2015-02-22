/*
 * This file is part of AlmuraSDK, licensed under the MIT License (MIT).
 *
 * Copyright (c) AlmuraDev <http://beta.almuramc.com/>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.almuradev.almurasdk.permissions;

import com.almuradev.almurasdk.AlmuraSDK;
import com.almuradev.almurasdk.server.network.play.S00PacketPermissions;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.eq2online.permissions.ReplicatedPermissionsContainer;
import net.minecraft.client.Minecraft;

import java.util.*;

/**
 * This class manages permissions on the client, it is a singleton class which can manage permissions for multiple
 * client mods. It manages the client/server communication used to replicate permissions and serves as a hub for
 * permissions objects which keep track of the permissions available on the client
 *
 * @author Adam Mummery-Smith
 */
@SideOnly(Side.CLIENT)
public class PermissionsManagerClient implements PermissionsManager {

    /**
     * Singleton instance of the client permissions manager
     */
    private static PermissionsManagerClient instance;

    /**
     * Permissions permissible which is a proxy for permissions that are common to all mods
     */
    private static Permissible allMods = new PermissibleAllMods();

    /**
     * List of registered client mods supporting permissions
     */
    private Map<String, Permissible> registeredClientMods = new HashMap<>();

    /**
     * List of registered client permissions, grouped by mod
     */
    private Map<Permissible, TreeSet<String>> registeredClientPermissions = new HashMap<>();

    /**
     * Objects which listen to events generated by this object
     */
    private Set<Permissible> permissibles = new HashSet<>();

    /**
     * Local permissions, used when server permissions are not available
     */
    private LocalPermissions localPermissions = new LocalPermissions();

    /**
     * Server permissions, indexed by mod
     */
    private Map<String, ServerPermissions> serverPermissions = new HashMap<>();

    /**
     * Delay counter for when joining a server
     */
    private int pendingRefreshTicks = 0;

    private int menuTicks = 0;

    /**
     * Private .ctor, for singleton pattern
     */
    private PermissionsManagerClient() {
        this.registerClientMod("all", allMods);
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * Get a reference to the singleton instance of the client permissions manager
     */
    public static PermissionsManagerClient getInstance() {
        if (instance == null) {
            instance = new PermissionsManagerClient();
        }

        return instance;
    }

    /**
     * @param modName
     * @param permission
     */
    protected static String formatModPermission(String modName, String permission) {
        return String.format("mod.%s.%s", modName, permission);
    }

    @Override
    public Permissions getPermissions(Permissible mod) {
        if (mod == null) {
            mod = allMods;
        }

        return getPermissions(mod.getPermissibleModName());
    }

    @Override
    public Permissions getPermissions(String permModId) {
        if (permModId == null) {
            permModId = allMods.getPermissibleModName();
        }

        ServerPermissions modPermissions = this.serverPermissions.get(permModId);
        return modPermissions != null ? modPermissions : this.localPermissions;
    }

    @Override
    public void registerPermissible(Permissible permissible) {
        if (!this.permissibles.contains(permissible) && permissible.getPermissibleModName() != null) {
            this.registerClientMod(permissible.getPermissibleModName(), permissible);
            permissible.registerPermissions(this);
        }

        this.permissibles.add(permissible);
    }

    @SubscribeEvent
    public void onClientConnectedToServerEvent(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        this.clearServerPermissions();
        this.scheduleRefresh();
    }

    @SubscribeEvent
    public void onTickEvent(TickEvent.ClientTickEvent event) {
        if (this.pendingRefreshTicks > 0) {
            this.pendingRefreshTicks--;

            if (this.pendingRefreshTicks == 0 && Minecraft.getMinecraft().inGameHasFocus) {
                this.sendPermissionQueries();
                return;
            }
        }

        for (Map.Entry<String, ServerPermissions> modPermissions : this.serverPermissions.entrySet()) {
            if (!modPermissions.getValue().isValid()) {
                modPermissions.getValue().notifyRefreshPending();
                this.sendPermissionQuery(this.registeredClientMods.get(modPermissions.getKey()));
            }
        }

        if (Minecraft.getMinecraft().inGameHasFocus) {
            this.menuTicks = 0;
        } else {
            this.menuTicks++;
        }

        if (this.menuTicks == 200) {
            this.clearServerPermissions();
        }

    }

    /**
     * Register a new client mod with this manager
     *
     * @param modName Mod name
     * @param mod     Mod instance
     */
    private void registerClientMod(String modName, Permissible mod) {
        if (this.registeredClientMods.containsKey(modName)) {
            throw new IllegalArgumentException(
                    "Cannot register mod \"" + modName + "\"! The mod was already registered with the permissions manager.");
        }

        this.registeredClientMods.put(modName, mod);
        this.registeredClientPermissions.put(mod, new TreeSet<String>());
    }

    /**
     * Schedule a permissions refresh
     */
    public void scheduleRefresh() {
        this.pendingRefreshTicks = 40;
    }

    /**
     * Clears the current replicated server permissions
     */
    protected void clearServerPermissions() {
        this.serverPermissions.clear();

        for (Permissible permissible : this.permissibles) {
            permissible.onPermissionsCleared(this);
        }
    }

    /**
     * Send permission query packets to the server for all registered mods
     */
    protected void sendPermissionQueries() {
        for (Permissible mod : this.registeredClientMods.values()) {
            this.sendPermissionQuery(mod);
        }
    }

    /**
     * Send a permission query packet to the server for the specified mod. You do not need to call this method because it is
     * issued automatically by the client permissions manager when connecting to a new server. However you can call use this
     * method to "force" a refresh of permissions when needed.
     *
     * @param mod mod to send a query packet for
     */
    public void sendPermissionQuery(Permissible mod) {
        String modName = mod.getPermissibleModName();

        if (Minecraft.getMinecraft().inGameHasFocus) {
            if (!this.registeredClientMods.containsValue(mod)) {
                throw new IllegalArgumentException("The specified mod \"" + modName + "\" was not registered with the permissions system");
            }

            Float modVersion = mod.getPermissibleModVersion();
            Set<String> modPermissions = this.registeredClientPermissions.get(mod);

            if (modPermissions != null) {
                ReplicatedPermissionsContainer query = new ReplicatedPermissionsContainer(modName, modVersion, modPermissions);

                if (!query.modName.equals("all") || query.permissions.size() > 0) {
                    AlmuraSDK.NETWORK_PERMISSIONS.sendToServer(new S00PacketPermissions(query));
                }
            }
        } else {
            this.serverPermissions.remove(modName);
        }
    }

    /**
     * Register a permission for all mods, the permission will be prefixed with "mod.all." to provide
     * a common namespace for client mods when permissions are replicated to the server
     *
     * @param permission
     */
    @Override
    public void registerPermission(String permission) {
        this.registerModPermission(allMods, permission);
    }

    /**
     * Register a permission for the specified mod, the permission will be prefixed with "mod.{modname}." to provide
     * a common namespace for client mods when permissions are replicated to the server
     *
     * @param mod
     * @param permission
     */
    @Override
    public void registerModPermission(Permissible mod, String permission) {
        if (mod == null) {
            mod = allMods;
        }
        String modName = mod.getPermissibleModName();

        if (!this.registeredClientMods.containsValue(mod)) {
            throw new IllegalArgumentException(
                    "Cannot register a mod permission for mod \"" + modName + "\"! The mod was not registered with the permissions manager.");
        }

        permission = formatModPermission(modName, permission);

        Set<String> modPermissions = this.registeredClientPermissions.get(mod);
        if (modPermissions != null && !modPermissions.contains(permission)) {
            modPermissions.add(permission);
        }
    }

    /**
     * Get the value of the specified permission for all mods.
     *
     * @param permission Permission to check for
     */
    @Override
    public boolean getPermission(String permission) {
        return this.getModPermission(allMods, permission);
    }

    /**
     * Get the value of the specified permission for all mods and return the default value if the permission is not set
     *
     * @param permission   Permission to check for
     * @param defaultValue Value to return if the permission is not set
     */
    @Override
    public boolean getPermission(String permission, boolean defaultValue) {
        return this.getModPermission(allMods, permission, defaultValue);
    }

    /**
     * Get the value of the specified permission for the specified mod. The permission will be prefixed with "mod.{modname}."
     * in keeping with registerModPermission as a convenience.
     *
     * @param mod
     * @param permission
     */
    @Override
    public boolean getModPermission(Permissible mod, String permission) {
        if (mod == null) {
            mod = PermissionsManagerClient.allMods;
        }
        permission = formatModPermission(mod.getPermissibleModName(), permission);
        Permissions permissions = this.getPermissions(mod);

        return permissions == null || permissions.hasPermission(permission);

    }

    /**
     * Get the value of the specified permission for the specified mod. The permission will be prefixed with "mod.{modname}."
     * in keeping with registerModPermission as a convenience.
     *
     * @param modName
     * @param permission
     */
    @Override
    public boolean getModPermission(String modName, String permission) {
        Permissible mod = this.registeredClientMods.get(modName);
        return mod != null && this.getModPermission(mod, permission);
    }

    /**
     * Get the value of the specified permission for the specified mod. The permission will be prefixed with "mod.{modname}."
     * in keeping with registerModPermission as a convenience. If the permission does not exist, the specified default value
     * will be returned.
     *
     * @param mod
     * @param permission
     * @param defaultValue
     */
    @Override
    public boolean getModPermission(Permissible mod, String permission, boolean defaultValue) {
        if (mod == null) {
            mod = allMods;
        }
        permission = formatModPermission(mod.getPermissibleModName(), permission);
        Permissions permissions = this.getPermissions(mod);

        if (permissions != null && permissions.hasPermissionSet(permission)) {
            return permissions.hasPermission(permission);
        }

        return defaultValue;
    }

    /**
     * Get the value of the specified permission for the specified mod. The permission will be prefixed with "mod.{modname}."
     * in keeping with registerModPermission as a convenience.
     *
     * @param modName
     * @param permission
     */
    @Override
    public boolean getModPermission(String modName, String permission, boolean defaultValue) {
        Permissible mod = this.registeredClientMods.get(modName);
        return mod != null ? this.getModPermission(mod, permission, defaultValue) : defaultValue;
    }

    public Permissible getForMod(String modid) {
        return registeredClientMods.get(modid);
    }

    public void putServerPermissions(String modid, ServerPermissions permissions) {
        serverPermissions.put(modid, permissions);
    }
}

