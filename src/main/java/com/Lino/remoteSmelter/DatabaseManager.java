package com.Lino.remoteSmelter;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.sql.*;
import java.util.*;

public class DatabaseManager {

    private final RemoteSmelter plugin;
    private Connection connection;
    private final String dbFile;

    public DatabaseManager(RemoteSmelter plugin) {
        this.plugin = plugin;
        this.dbFile = plugin.getDataFolder() + File.separator + "smelters.db";
    }

    public void initialize() throws SQLException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        connect();
        createTables();
    }

    private void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    private void createTables() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS smelters (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "uuid TEXT NOT NULL," +
                "name TEXT NOT NULL," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "UNIQUE(uuid, name))";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean addSmelter(UUID playerUUID, String name, Location location) {
        String sql = "INSERT INTO smelters (uuid, name, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUUID.toString());
            pstmt.setString(2, name);
            pstmt.setString(3, location.getWorld().getName());
            pstmt.setInt(4, location.getBlockX());
            pstmt.setInt(5, location.getBlockY());
            pstmt.setInt(6, location.getBlockZ());
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean removeSmelter(UUID playerUUID, String name) {
        String sql = "DELETE FROM smelters WHERE uuid = ? AND name = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUUID.toString());
            pstmt.setString(2, name);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void removeSmelterByLocation(Location location) {
        String sql = "DELETE FROM smelters WHERE world = ? AND x = ? AND y = ? AND z = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, location.getWorld().getName());
            pstmt.setInt(2, location.getBlockX());
            pstmt.setInt(3, location.getBlockY());
            pstmt.setInt(4, location.getBlockZ());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Map<UUID, Map<String, RemoteSmelter.SmelterData>> loadAllSmelters() {
        Map<UUID, Map<String, RemoteSmelter.SmelterData>> result = new HashMap<>();
        String sql = "SELECT uuid, name, world, x, y, z FROM smelters";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                String name = rs.getString("name");
                String worldName = rs.getString("world");

                if (Bukkit.getWorld(worldName) == null) {
                    continue;
                }

                Location loc = new Location(
                        Bukkit.getWorld(worldName),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z")
                );

                result.computeIfAbsent(uuid, k -> new HashMap<>())
                        .put(name, new RemoteSmelter.SmelterData(name, loc));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }

    public Map<String, RemoteSmelter.SmelterData> getPlayerSmelters(UUID playerUUID) {
        Map<String, RemoteSmelter.SmelterData> result = new HashMap<>();
        String sql = "SELECT name, world, x, y, z FROM smelters WHERE uuid = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUUID.toString());

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    String worldName = rs.getString("world");

                    if (Bukkit.getWorld(worldName) == null) {
                        continue;
                    }

                    Location loc = new Location(
                            Bukkit.getWorld(worldName),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z")
                    );

                    result.put(name, new RemoteSmelter.SmelterData(name, loc));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }

    public boolean smelterExists(UUID playerUUID, String name) {
        String sql = "SELECT COUNT(*) FROM smelters WHERE uuid = ? AND name = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUUID.toString());
            pstmt.setString(2, name);

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean isLocationRegistered(Location location) {
        String sql = "SELECT COUNT(*) FROM smelters WHERE world = ? AND x = ? AND y = ? AND z = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, location.getWorld().getName());
            pstmt.setInt(2, location.getBlockX());
            pstmt.setInt(3, location.getBlockY());
            pstmt.setInt(4, location.getBlockZ());

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public Map.Entry<UUID, String> getSmelterOwner(Location location) {
        String sql = "SELECT uuid, name FROM smelters WHERE world = ? AND x = ? AND y = ? AND z = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, location.getWorld().getName());
            pstmt.setInt(2, location.getBlockX());
            pstmt.setInt(3, location.getBlockY());
            pstmt.setInt(4, location.getBlockZ());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("name");
                    return new AbstractMap.SimpleEntry<>(uuid, name);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }
}