/**
 * wodeTeam is pleased to support the open source community by making AntiXray available.
 * 
 * Copyright (C) 2019  Woder
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0>.
 */

package cn.wode490390.nukkit.antixray;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockUpdateEvent;
import cn.nukkit.event.level.ChunkUnloadEvent;
import cn.nukkit.event.level.LevelUnloadEvent;
import cn.nukkit.event.player.PlayerChunkRequestEvent;
import cn.nukkit.level.GlobalBlockPalette;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.stream.FastByteArrayOutputStream;
import cn.nukkit.network.protocol.UpdateBlockPacket;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.utils.Config;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import net.openhft.hashing.LongHashFunction;

public class AntiXray extends PluginBase implements Listener {

    public final static String PERMISSION_WHITELIST = "antixray.whitelist";

    static LongHashFunction XX64;

    static File CACHE_DIR;

    int height;
    boolean mode;
    boolean memoryCache;
    boolean localCache;
    int fake_o;
    int fake_n;
    IntList ores;
    IntList filters;
    private List<String> worlds;

    private final Map<Level, WorldHandler> handlers = Maps.newHashMap();

    @Override
    public void onEnable() {
        try {
            new MetricsLite(this);
        } catch (Exception ignore) {

        }
        this.saveDefaultConfig();
        Config config = this.getConfig();
        String node = "scan-height-limit";
        try {
            this.height = config.getInt(node, 64);
        } catch (Exception e) {
            this.height = 64;
            this.logLoadException(node, e);
        }
        if (this.height < 1) {
            this.height = 1;
        } else if (this.height > 255) {
            this.height = 255;
        }
        node = "memory-cache";
        if (config.exists(node)) {
            try {
                this.memoryCache = config.getBoolean(node, true);
            } catch (Exception e) {
                this.memoryCache = true;
                this.logLoadException(node, e);
            }
        } else { //compatible
            node = "cache-chunks";
            try {
                this.memoryCache = config.getBoolean(node, true);
            } catch (Exception e) {
                this.memoryCache = true;
                this.logLoadException("memory-cache", e);
            }
        }
        node = "local-cache";
        try {
            this.localCache = config.getBoolean(node, true);
        } catch (Exception e) {
            this.localCache = true;
            this.logLoadException(node, e);
        }
        node = "obfuscator-mode";
        try {
            this.mode = config.getBoolean(node, true);
        } catch (Exception e) {
            this.mode = true;
            this.logLoadException(node, e);
        }
        node = "overworld-fake-block";
        try {
            this.fake_o = config.getInt(node, 1);
        } catch (Exception e) {
            this.fake_o = 1;
            this.logLoadException(node, e);
        }
        node = "nether-fake-block";
        try {
            this.fake_n = config.getInt(node, 87);
        } catch (Exception e) {
            this.fake_n = 87;
            this.logLoadException(node, e);
        }
        node = "protect-worlds";
        try {
            this.worlds = config.getStringList(node);
        } catch (Exception e) {
            this.logLoadException(node, e);
        }
        node = "ores";
        try {
            this.ores = new IntArrayList(new IntOpenHashSet(config.getIntegerList(node)));
        } catch (Exception e) {
            this.logLoadException(node, e);
        }

        if (this.worlds != null && this.ores != null && !this.worlds.isEmpty() && !this.ores.isEmpty()) {
            node = "filters";
            try {
                this.filters = new IntArrayList(new IntOpenHashSet(config.getIntegerList(node)));
            } catch (Exception e) {
                this.filters = new IntArrayList(0);
                this.logLoadException(node, e);
            }

            if (this.localCache) {
                CACHE_DIR = new File(this.getDataFolder(), "cache");
                if (!CACHE_DIR.exists()) {
                    CACHE_DIR.mkdirs();
                } else if (!CACHE_DIR.isDirectory()) {
                    CACHE_DIR.delete();
                    CACHE_DIR.mkdirs();
                }
                if (!CACHE_DIR.exists() || !CACHE_DIR.isDirectory()) {
                    this.localCache = false;
                    this.getLogger().warning("Failed to initialize cache! Disabled cache.");
                } else {
                    XX64 = LongHashFunction.xx();
                }
            }
            this.getServer().getPluginManager().registerEvents(this, this);
            if (this.memoryCache) {
                this.getServer().getPluginManager().registerEvents(new CleanerListener(), this);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChunkRequest(PlayerChunkRequestEvent event) {
        Player player = event.getPlayer();
        Level level = player.getLevel();
        if (!player.hasPermission(PERMISSION_WHITELIST) && this.worlds.contains(level.getName()) && player.getLoaderId() > 0) {
            event.setCancelled();
            WorldHandler handler = this.handlers.get(level);
            if (handler == null) {
                handler = new WorldHandler(this, level);
                handler.start();
                this.handlers.put(level, handler);
            }
            handler.requestChunk(event.getChunkX(), event.getChunkZ(), player);
        }
    }

    @EventHandler
    //TODO: Use BlockBreakEvent instead of BlockUpdateEvent
    public void onBlockUpdate(BlockUpdateEvent event) {
        Position position = event.getBlock();
        Level level = position.getLevel();
        if (this.worlds.contains(level.getName())) {
            List<UpdateBlockPacket> packets = new ObjectArrayList<>();
            for (Vector3 vector : new Vector3[]{
                    position.add(1),
                    position.add(-1),
                    position.add(0, 1),
                    position.add(0, -1),
                    position.add(0, 0, 1),
                    position.add(0, 0, -1)
            }) {
                int y = vector.getFloorY();
                if (y > 255 || y < 0) {
                    continue;
                }
                int x = vector.getFloorX();
                int z = vector.getFloorZ();
                UpdateBlockPacket packet = new UpdateBlockPacket();
                try {
                    packet.blockRuntimeId = GlobalBlockPalette.getOrCreateRuntimeId(level.getFullBlock(x, y, z));
                } catch (Exception tryAgain) {
                    try {
                        packet.blockRuntimeId = GlobalBlockPalette.getOrCreateRuntimeId(level.getBlockIdAt(x, y, z), 0);
                    } catch (Exception ex) {
                        continue;
                    }
                }
                packet.x = x;
                packet.y = y;
                packet.z = z;
                packet.flags = UpdateBlockPacket.FLAG_ALL_PRIORITY;
                packets.add(packet);
            }
            if (packets.size() > 0) {
                /*Set<Player> players = Sets.newHashSet();
                level.getChunkPlayers(position.getChunkX(), position.getChunkZ()).values().stream()
                        .filter(player -> !player.hasPermission(PERMISSION_WHITELIST))
                        .forEach(player -> players.add(player));
                this.getServer().batchPackets(players.toArray(new Player[0]), packets.toArray(new UpdateBlockPacket[0]));*/
                this.getServer().batchPackets(level.getChunkPlayers(position.getChunkX(), position.getChunkZ()).values().toArray(new Player[0]), packets.toArray(new UpdateBlockPacket[0]));
            }
        }
    }

    @EventHandler
    public void onLevelUnload(LevelUnloadEvent event) {
        Level level = event.getLevel();
        WorldHandler handler = this.handlers.get(level);
        if (handler != null) {
            handler.interrupt();
            this.handlers.remove(level);
        }
    }

    long getCacheHash(byte[] buffer) {
        return XX64.hashBytes(buffer);
    }

    boolean hasCache(long hash) {
        File file = new File(CACHE_DIR, String.valueOf(hash));
        return file.exists() && !file.isDirectory();
    }

    void createCache(long hash, byte[] buffer) {
        this.getServer().getScheduler().scheduleAsyncTask(this, new CacheWriteTask(hash, buffer));
    }

    byte[] readCache(long hash) {
        File file = new File(CACHE_DIR, String.valueOf(hash));
        try {
            if (!file.exists() || file.isDirectory()) {
                throw new FileNotFoundException();
            } else if (file.length() == 0) {
                throw new EOFException();
            }
            try (InputStream inputStream = new InflaterInputStream(new BufferedInputStream(new FileInputStream(file)), new Inflater(true)); FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream(1024)) {
                byte[] temp = new byte[1024];
                int length;
                while ((length = inputStream.read(temp)) != -1) {
                    outputStream.write(temp, 0, length);
                }
                return outputStream.toByteArray();
            }
        } catch (IOException e) {
            this.getLogger().debug("Unable to read cache file", e);
        }
        return null;
    }

    private void logLoadException(String node, Exception ex) {
        this.getLogger().alert("Failed to get the configuration '" + node + "'. Use the default value.", ex);
    }

    private static boolean deleteFolder(File file) {
        if (file.isDirectory()) {
            for (String children : file.list()) {
                boolean success = deleteFolder(new File(file, children));
                if (!success) {
                    return false;
                }
            }
        }
        return file.delete();
    }

    public class CleanerListener implements Listener {

        @EventHandler
        public void onChunkUnload(ChunkUnloadEvent event) {
            WorldHandler handler = AntiXray.this.handlers.get(event.getLevel());
            if (handler != null) {
                FullChunk chunk = event.getChunk();
                handler.clearCache(chunk.getX(), chunk.getZ());
            }
        }
    }

    private class CacheWriteTask extends AsyncTask {

        private final long hash;
        private final byte[] buffer;

        private CacheWriteTask(long hash, byte[] buffer) {
            this.hash = hash;
            this.buffer = buffer;
        }

        @Override
        public void onRun() {
            try {
                File file = new File(CACHE_DIR, String.valueOf(hash));
                if (!file.exists()) {
                    file.createNewFile();
                } else if (file.isDirectory()) {
                    deleteFolder(file);
                    file.createNewFile();
                }

                try (DeflaterOutputStream outputStream = new DeflaterOutputStream(new BufferedOutputStream(new FileOutputStream(file)), new Deflater(Deflater.BEST_COMPRESSION, true)); InputStream inputStream = new ByteArrayInputStream(this.buffer)) {
                    byte[] temp = new byte[1024];
                    int length;
                    while ((length = inputStream.read(temp)) != -1) {
                        outputStream.write(temp, 0, length);
                    }
                    outputStream.finish();
                }
            } catch (IOException e) {
                AntiXray.this.getLogger().debug("Unable to save cache file", e);
            }
        }
    }
}
