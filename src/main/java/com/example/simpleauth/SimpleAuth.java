package com.example.simpleauth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleAuth extends JavaPlugin implements Listener {

    // Thread-safe (onChat runs asynchronously)
    private final Set<UUID> unauthed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> kickTasks = new ConcurrentHashMap<>();

    // Persisted across restarts
    private final Set<UUID> rememberedPlayers = ConcurrentHashMap.newKeySet();
    private File rememberedFile;
    private YamlConfiguration rememberedConfig;

    private String password;
    private int timeoutSeconds;
    private boolean rememberPlayers;
    private String language;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRememberedPlayers();
        reload();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SimpleAuth enabled. timeout=" + timeoutSeconds + "s rememberPlayers=" + rememberPlayers + " language=" + language);
    }

    private void reload() {
        reloadConfig();
        password = getConfig().getString("password", "password");
        timeoutSeconds = getConfig().getInt("timeout-seconds", 30);
        rememberPlayers = getConfig().getBoolean("remember-players", true);
        language = getConfig().getString("language", "en");
    }

    private String msg(String en, String ja) {
        return "ja".equalsIgnoreCase(language) ? ja : en;
    }

    private void loadRememberedPlayers() {
        rememberedFile = new File(getDataFolder(), "authed_players.yml");
        if (!rememberedFile.exists()) {
            try {
                getDataFolder().mkdirs();
                rememberedFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Failed to create authed_players.yml: " + e.getMessage());
            }
        }
        rememberedConfig = YamlConfiguration.loadConfiguration(rememberedFile);
        rememberedPlayers.clear();
        for (String uuidStr : rememberedConfig.getStringList("players")) {
            try {
                rememberedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {}
        }
        getLogger().info("Loaded " + rememberedPlayers.size() + " authenticated player(s).");
    }

    private void saveRememberedPlayer(UUID id) {
        rememberedPlayers.add(id);
        List<String> uuids = new ArrayList<>();
        for (UUID uuid : rememberedPlayers) {
            uuids.add(uuid.toString());
        }
        rememberedConfig.set("players", uuids);
        try {
            rememberedConfig.save(rememberedFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save authed_players.yml: " + e.getMessage());
        }
    }

    // ---------- Join / Quit ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        if (rememberPlayers && rememberedPlayers.contains(id)) {
            p.sendMessage(msg("§a✔ Auto-login successful. Welcome!", "§a✔ 自動ログインしました。ようこそ!"));
            return;
        }

        unauthed.add(id);

        p.sendMessage("§6=====================================");
        p.sendMessage(msg("§e[Auth] §fAuthentication required.", "§e[認証] §f認証が必要です。"));
        p.sendMessage(msg("§e[Auth] §fYou have §c" + timeoutSeconds + " seconds§f to authenticate.", "§e[認証] §c" + timeoutSeconds + "秒§f以内に認証してください。"));
        p.sendMessage(msg("§e[Auth] §fType the password in chat, or", "§e[認証] §fチャットでパスワードを入力するか、"));
        p.sendMessage(msg("§e[Auth] §fuse §a/auth <password>§f.", "§e[認証] §f§a/auth <パスワード>§f と入力してください。"));
        p.sendMessage("§6=====================================");

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (unauthed.contains(id)) {
                Player still = Bukkit.getPlayer(id);
                if (still != null && still.isOnline()) {
                    still.kickPlayer(msg(
                            "§cAuthentication timed out\n§7You did not enter the password in time.",
                            "§c認証時間切れ\n§7時間内にパスワードを入力しませんでした。"
                    ));
                }
            }
        }, timeoutSeconds * 20L);

        kickTasks.put(id, task);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        unauthed.remove(id);
        BukkitTask t = kickTasks.remove(id);
        if (t != null) t.cancel();
    }

    // ---------- Restrictions while unauthenticated ----------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!unauthed.contains(e.getPlayer().getUniqueId())) return;
        if (e.getTo() == null) return;
        if (e.getFrom().getX() != e.getTo().getX()
                || e.getFrom().getY() != e.getTo().getY()
                || e.getFrom().getZ() != e.getTo().getZ()) {
            Location to = e.getFrom().clone();
            to.setYaw(e.getTo().getYaw());
            to.setPitch(e.getTo().getPitch());
            e.setTo(to);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        if (!unauthed.contains(id)) return;

        // Always cancel unauthenticated chat — never visible to others
        e.setCancelled(true);

        final String chatMsg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!unauthed.contains(id)) return;
            if (chatMsg.equals(password)) {
                authenticate(player);
            } else {
                player.sendMessage(msg("§cAuthentication required.", "§cまず認証してください。"));
                player.sendMessage(msg("§7Type the password in chat or use §a/auth <password>§7.", "§7チャットでパスワードを入力するか §a/auth <パスワード>§7 と入力。"));
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPreCommand(PlayerCommandPreprocessEvent e) {
        if (!unauthed.contains(e.getPlayer().getUniqueId())) return;
        String cmdMsg = e.getMessage().toLowerCase(Locale.ROOT);
        if (!cmdMsg.startsWith("/auth")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("§cAuthentication required. Use §a/auth <password>", "§cまず認証してください。 §a/auth <パスワード>"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (unauthed.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (unauthed.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (unauthed.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && unauthed.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ---------- Authentication ----------

    private void authenticate(Player p) {
        UUID id = p.getUniqueId();
        if (!unauthed.remove(id)) return;
        BukkitTask t = kickTasks.remove(id);
        if (t != null) t.cancel();
        p.sendMessage(msg("§a✔ Authentication successful. Welcome!", "§a✔ 認証に成功しました。ようこそ!"));
        getLogger().info(p.getName() + " authenticated successfully.");
        String opMsg = msg(
                "§7[SimpleAuth] §a" + p.getName() + " §7authenticated successfully.",
                "§7[SimpleAuth] §a" + p.getName() + " §7が認証に成功しました。"
        );
        for (Player op : Bukkit.getOnlinePlayers()) {
            if (op.isOp()) {
                op.sendMessage(opMsg);
            }
        }
        if (rememberPlayers) {
            saveRememberedPlayer(id);
        }
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("authreload")) {
            if (!sender.hasPermission("simpleauth.reload")) {
                sender.sendMessage(msg("§cYou don't have permission.", "§c権限がありません。"));
                return true;
            }
            reload();
            loadRememberedPlayers();
            sender.sendMessage(msg(
                    "§aSimpleAuth config reloaded. timeout=" + timeoutSeconds + "s language=" + language,
                    "§aSimpleAuth の設定を再読み込みしました。 timeout=" + timeoutSeconds + "秒 language=" + language
            ));
            return true;
        }

        if (command.getName().equalsIgnoreCase("authforget")) {
            if (!(sender instanceof Player pf)) {
                sender.sendMessage(msg("This command is for players only.", "プレイヤー専用コマンドです。"));
                return true;
            }
            UUID fid = pf.getUniqueId();
            if (rememberedPlayers.remove(fid)) {
                List<String> uuids = new ArrayList<>();
                for (UUID uuid : rememberedPlayers) uuids.add(uuid.toString());
                rememberedConfig.set("players", uuids);
                try { rememberedConfig.save(rememberedFile); } catch (IOException ex) {
                    getLogger().warning("Failed to save authed_players.yml: " + ex.getMessage());
                }
                pf.sendMessage(msg(
                        "§aAuto-login removed. You will need to enter the password next time.",
                        "§a自動ログインの登録を解除しました。次回参加時はパスワードが必要です。"
                ));
            } else {
                pf.sendMessage(msg("§7You are not registered for auto-login.", "§7自動ログインには登録されていません。"));
            }
            return true;
        }

        // /auth command
        if (!(sender instanceof Player p)) {
            sender.sendMessage(msg("This command is for players only.", "プレイヤー専用コマンドです。"));
            return true;
        }
        UUID id = p.getUniqueId();

        if (!unauthed.contains(id)) {
            p.sendMessage(msg("§aYou are already authenticated.", "§aすでに認証済みです。"));
            return true;
        }

        if (args.length != 1) {
            p.sendMessage(msg("§cUsage: /auth <password>", "§c使い方: /auth <パスワード>"));
            return true;
        }

        if (args[0].equals(password)) {
            authenticate(p);
        } else {
            p.sendMessage(msg("§c✘ Wrong password. Please try again.", "§c✘ パスワードが違います。もう一度入力してください。"));
        }
        return true;
    }
}
