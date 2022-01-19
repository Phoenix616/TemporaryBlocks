package de.themoep.temporaryblocks;

import com.google.common.collect.ImmutableMap;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Level;

/*
 * TemporaryBlocks
 * Copyright (C) 2022 Max Lee aka Phoenix616 (max@themoep.de)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

public final class TemporaryBlocks extends JavaPlugin implements Listener {

    private long timerInterval = 1;
    private long allTime = 0;
    private final Map<Material, Long> resetTimes = new EnumMap<>(Material.class);

    private ConfigAccessor storedQueue;

    private final Queue<QueueEntry> removeQueue = new ArrayDeque<>();
    private BukkitTask task = null;

    @Override
    public void onEnable() {
        loadConfig();
        storedQueue = new ConfigAccessor(this, "queue.yml");
        List<Map<?, ?>> queue = storedQueue.getConfig().getMapList("queue");
        for (Map<?, ?> map : queue) {
            try {
                removeQueue.add(QueueEntry.deserialize(map));
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error in queue.yml", e);
            }
        }
        getCommand("temporaryblocks").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        if (!removeQueue.isEmpty()) {
            startRemovalTask();
        }
    }

    private void startRemovalTask() {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                QueueEntry entry;
                while ((entry = removeQueue.peek()) != null) {
                    if (entry.removeAt <= System.currentTimeMillis()) {
                        if (entry.location.getWorld() != null) {
                            if (entry.location.isChunkLoaded()) {
                                entry.removeBlockFromWorld();
                            } else {
                                QueueEntry finalEntry = entry;
                                entry.location.getWorld().getChunkAtAsync(entry.location).thenAccept(chunk -> {
                                    if (chunk != null) {
                                        finalEntry.removeBlockFromWorld();
                                    }
                                });
                            }
                            // Remove element
                            removeQueue.poll();
                        }
                    } else {
                        // Reached point in queue that doesn't need to be removed yet
                        break;
                    }
                }

                // Check if queue is empty and if so cancel task
                if (removeQueue.isEmpty() && task != null) {
                    task.cancel();
                    task = null;
                }
            }
        }.runTaskTimer(this, timerInterval, timerInterval);
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        List<Map<?, ?>> queueList = new ArrayList<>();
        for (QueueEntry entry : removeQueue) {
            queueList.add(entry.serialize());
        }
        storedQueue.getConfig().set("queue", queueList);
        storedQueue.saveConfig();
    }

    private long getTime(Block block) {
        return getTime(block.getType());
    }

    private long getTime(Material type) {
        return resetTimes.getOrDefault(type, allTime);
    }

    // block events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        long time = getTime(event.getBlock());
        if (time > 0) {
            addQueued(event.getBlock(), time);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        removeQueued(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        removeQueued(event.getBlock());
        for (Block block : event.blockList()) {
            removeQueued(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            removeQueued(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getTo().isEmpty()) {
            removeQueued(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockMove(BlockPistonExtendEvent event) {
        handleBlocksMove(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockMove(BlockPistonRetractEvent event) {
        handleBlocksMove(event.getBlocks(), event.getDirection());
    }

    private void handleBlocksMove(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            QueueEntry entry = getEntry(block);
            if (entry != null) {
                entry.location.add(direction.getDirection());
            }
        }
    }

    // Entity block events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.HAND && event.getItem() != null && event.getClickedBlock() != null) {
            EntityType entityType = getEntityTypeFromMaterial(event.getItem().getType());
            if (entityType != null && entityType != EntityType.PAINTING && entityType != EntityType.ITEM_FRAME) { // painting and item frame are handled by hanging event
                long time = getTime(event.getItem().getType());
                if (time > 0) {
                    addQueued(event.getClickedBlock().getLocation().add(event.getBlockFace().getDirection()), event.getItem().getType(), time);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Material mappedType = getMaterialFromEntity(event.getEntity());
        if (mappedType != null) {
            long time = getTime(mappedType);
            if (time > 0) {
                addQueued(event.getEntity().getLocation(), mappedType, time);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        handleEntityRemove(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        handleEntityRemove(event.getEntity());
    }

    private void handleEntityRemove(Entity entity) {
        Material mappedType = getMaterialFromEntity(entity);
        if (mappedType != null) {
            long time = getTime(mappedType);
            if (time > 0) {
                removeQueued(entity.getLocation());
            }
        }
    }

    private void addQueued(Block block, long time) {
        addQueued(block.getLocation(), block.getType(), time);
    }

    private void addQueued(Location location, Material type, long time) {
        removeQueue.add(new QueueEntry(location, System.currentTimeMillis() + time * 1000, type));
        if (task == null) {
            startRemovalTask();
        }
    }

    private void removeQueued(Block block) {
        removeQueued(block.getLocation());
    }

    private void removeQueued(Location location) {
        for (Iterator<QueueEntry> it = removeQueue.iterator(); it.hasNext(); ) {
            QueueEntry entry = it.next();
            if (entry.location.equals(location)) {
                it.remove();
                break;
            }
        }

        // Check if queue is empty and if so cancel task
        if (removeQueue.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private QueueEntry getEntry(Block block) {
        Location location = block.getLocation();
        for (QueueEntry entry : removeQueue) {
            if (entry.location.equals(location)) {
                return entry;
            }
        }
        return null;
    }

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0) {
            if ("list".equalsIgnoreCase(args[0])) {
                sender.sendMessage(ChatColor.GREEN + "Reset times:");
                for (Map.Entry<Material, Long> entry : resetTimes.entrySet()) {
                    sender.sendMessage(entry.getKey().name() + ": " + entry.getValue() + "s");
                }
            } else if ("reload".equalsIgnoreCase(args[0])) {
                loadConfig();
                sender.sendMessage(ChatColor.GREEN + "Config reloaded!");
            }
        }
        return true;
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();

        timerInterval = getConfig().getLong("timer-interval", 1);

        resetTimes.clear();
        allTime = 0;
        ConfigurationSection resetTimesConfig = getConfig().getConfigurationSection("reset-times");
        if (resetTimesConfig != null) {
            getLogger().info("Loading reset-times...");
            for (String materialString : resetTimesConfig.getKeys(false)) {
                if ("*".equals(materialString)) {
                    allTime = resetTimesConfig.getLong(materialString);
                    getLogger().info(materialString + ": " + allTime + "s");
                } else {
                    List<Material> materials = matchMaterials(materialString);
                    if (!materials.isEmpty()) {
                        long seconds = resetTimesConfig.getLong(materialString);
                        if (seconds > 0) {
                            for (Material material : materials) {
                                resetTimes.put(material, seconds);
                                getLogger().info(material.name() + ": " + seconds + "s");
                            }
                        }
                    } else {
                        getLogger().severe(materialString + " in config.yml is not a valid Material definition!");
                    }
                }
            }
        } else {
            getLogger().warning("No reset-times configured?");
        }
    }

    private List<Material> matchMaterials(String materialString) {
        Material material = Material.matchMaterial(materialString);
        if (material != null) {
            return Collections.singletonList(material);
        }

        materialString = materialString.toLowerCase(Locale.ROOT);
        List<Material> materials = new ArrayList<>();
        if (materialString.startsWith("*") && materialString.endsWith("*")) {
            String containsString = materialString.substring(1, materialString.length() -1);
            for (Material m : Material.values()) {
                if (m.name().toLowerCase(Locale.ROOT).contains(containsString)) {
                    materials.add(m);
                }
            }
        } else if (materialString.endsWith("*")) {
            String startString = materialString.substring(0, materialString.length() - 1);
            for (Material m : Material.values()) {
                if (m.name().toLowerCase(Locale.ROOT).startsWith(startString)) {
                    materials.add(m);
                }
            }
        } else if (materialString.startsWith("*")) {
            String endString = materialString.substring(1);
            for (Material m : Material.values()) {
                if (m.name().toLowerCase(Locale.ROOT).endsWith(endString)) {
                    materials.add(m);
                }
            }
        }
        return materials;
    }

    private static class QueueEntry {
        private Location location;
        private final long removeAt;
        private final Material type;

        private QueueEntry(Location location, long removeAt, Material type) {
            this.location = location;
            this.type = type;
            this.removeAt = removeAt;
        }

        private void removeBlockFromWorld() {
            if (type.isBlock()) {
                Block block = location.getBlock();
                block.setType(Material.AIR);
            } else {
                // Check if block placed entity
                EntityType entityType = getEntityTypeFromMaterial(type);
                if (entityType != null) {
                    for (Entity entity : location.getWorld().getNearbyEntitiesByType(entityType.getEntityClass(), location.toCenterLocation(), 0.5)) {
                        entity.remove();
                    }
                }
            }
        }

        public Map<String, Object> serialize() {
            return ImmutableMap.of("location", location, "removeAt", removeAt, "type", type.name());
        }

        public static QueueEntry deserialize(Map<?, ?> map) {
            if (map.containsKey("location") && map.containsKey("removeAt") && map.containsKey("type")) {
                Location location = (Location) map.get("location");
                long removeAt = Long.parseLong(String.valueOf(map.get("removeAt")));
                Material type = Material.valueOf((String) map.get("type"));
                return new QueueEntry(location, removeAt, type);
            }
            throw new IllegalArgumentException("Invalid map for QueueEntry: " + map);
        }
    }

    private static EntityType getEntityTypeFromMaterial(Material type) {
        switch (type) {
            case END_CRYSTAL: return EntityType.ENDER_CRYSTAL;
            case ARMOR_STAND: return EntityType.ARMOR_STAND;
            case ITEM_FRAME: return EntityType.ITEM_FRAME;
            case PAINTING: return EntityType.PAINTING;
        }
        return null;
    }

    private static Material getMaterialFromEntity(Entity entity) {
        switch (entity.getType()) {
            case ENDER_CRYSTAL: return Material.END_CRYSTAL;
            case ARMOR_STAND: return Material.ARMOR_STAND;
            case ITEM_FRAME: return Material.ITEM_FRAME;
            case PAINTING: return Material.PAINTING;
        }
        return null;
    }
}
