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

/**
 * SimpleAuth - シンプルな認証プラグイン。
 * 参加してから一定時間内に /auth <パスワード> またはチャットでパスワードを
 * 入力しないとキックする。
 * remember-players が true の場合、一度認証したプレイヤーは次回以降自動ログイン。
 */
public final class SimpleAuth extends JavaPlugin implements Listener {

    // スレッドセーフ（onChat が非同期で動くため）
    private final Set<UUID> unauthed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> kickTasks = new ConcurrentHashMap<>();

    // 一度認証済みのプレイヤー（再起動後も保持）
    private final Set<UUID> rememberedPlayers = ConcurrentHashMap.newKeySet();
    private File rememberedFile;
    private YamlConfiguration rememberedConfig;

    private String password;
    private int timeoutSeconds;
    private boolean rememberPlayers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRememberedPlayers();
        reload();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SimpleAuth を有効化しました。 timeout=" + timeoutSeconds + "秒 rememberPlayers=" + rememberPlayers);
    }

    private void reload() {
        reloadConfig();
        password = getConfig().getString("password", "ois");
        timeoutSeconds = getConfig().getInt("timeout-seconds", 30);
        rememberPlayers = getConfig().getBoolean("remember-players", true);
    }

    private void loadRememberedPlayers() {
        rememberedFile = new File(getDataFolder(), "authed_players.yml");
        if (!rememberedFile.exists()) {
            try {
                getDataFolder().mkdirs();
                rememberedFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("authed_players.yml の作成に失敗しました: " + e.getMessage());
            }
        }
        rememberedConfig = YamlConfiguration.loadConfiguration(rememberedFile);
        rememberedPlayers.clear();
        for (String uuidStr : rememberedConfig.getStringList("players")) {
            try {
                rememberedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {}
        }
        getLogger().info("認証済みプレイヤー " + rememberedPlayers.size() + " 人をロードしました。");
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
            getLogger().warning("authed_players.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    // ---------- 参加 / 退出 ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // 自動ログイン
        if (rememberPlayers && rememberedPlayers.contains(id)) {
            p.sendMessage("§a✔ 自動ログインしました。ようこそ!");
            return;
        }

        unauthed.add(id);

        p.sendMessage("§6=====================================");
        p.sendMessage("§e[認証] §f認証が必要です。");
        p.sendMessage("§e[認証] §c" + timeoutSeconds + "秒§f以内に、");
        p.sendMessage("§e[認証] §f チャットでパスワードを入力するか");
        p.sendMessage("§e[認証] §f §a/auth <パスワード>§f と入力してください。");
        p.sendMessage("§6=====================================");

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (unauthed.contains(id)) {
                Player still = Bukkit.getPlayer(id);
                if (still != null && still.isOnline()) {
                    still.kickPlayer("§c認証時間切れ\n§7時間内にパスワードを入力しませんでした。");
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

    // ---------- 未認証中の制限 ----------

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

    /**
     * チャットを認証入力としても受け付ける。
     * 未認証のプレイヤーのチャットは常にキャンセル（他人には絶対見えない）。
     * 内容がパスワードと一致したら認証成功扱い。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        if (!unauthed.contains(id)) return;

        // 未認証のチャットは必ずキャンセル（公開チャットには絶対流れない）
        e.setCancelled(true);

        final String msg = e.getMessage().trim();
        // 認証処理はメインスレッドで実行
        Bukkit.getScheduler().runTask(this, () -> {
            if (!unauthed.contains(id)) return;
            if (msg.equals(password)) {
                authenticate(player);
            } else {
                player.sendMessage("§cまず認証してください。");
                player.sendMessage("§7チャットでパスワードを入力するか §a/auth <パスワード>§7 と入力。");
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPreCommand(PlayerCommandPreprocessEvent e) {
        if (!unauthed.contains(e.getPlayer().getUniqueId())) return;
        String msg = e.getMessage().toLowerCase(Locale.ROOT);
        if (!msg.startsWith("/auth")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cまず認証してください。 §a/auth <パスワード>");
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

    // ---------- 認証処理 ----------

    private void authenticate(Player p) {
        UUID id = p.getUniqueId();
        if (!unauthed.remove(id)) return;
        BukkitTask t = kickTasks.remove(id);
        if (t != null) t.cancel();
        p.sendMessage("§a✔ 認証に成功しました。ようこそ!");
        getLogger().info(p.getName() + " が認証に成功しました。");
        if (rememberPlayers) {
            saveRememberedPlayer(id);
        }
    }

    // ---------- コマンド ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("authreload")) {
            if (!sender.hasPermission("simpleauth.reload")) {
                sender.sendMessage("§c権限がありません。");
                return true;
            }
            reload();
            loadRememberedPlayers();
            sender.sendMessage("§aSimpleAuth の設定を再読み込みしました。 timeout=" + timeoutSeconds + "秒");
            return true;
        }

        // /authforget - 自分の自動ログイン登録を解除
        if (command.getName().equalsIgnoreCase("authforget")) {
            if (!(sender instanceof Player pf)) {
                sender.sendMessage("プレイヤー専用コマンドです。");
                return true;
            }
            UUID fid = pf.getUniqueId();
            if (rememberedPlayers.remove(fid)) {
                List<String> uuids = new ArrayList<>();
                for (UUID uuid : rememberedPlayers) uuids.add(uuid.toString());
                rememberedConfig.set("players", uuids);
                try { rememberedConfig.save(rememberedFile); } catch (IOException ex) {
                    getLogger().warning("authed_players.yml の保存に失敗しました: " + ex.getMessage());
                }
                pf.sendMessage("§a自動ログインの登録を解除しました。次回参加時はパスワードが必要です。");
            } else {
                pf.sendMessage("§7自動ログインには登録されていません。");
            }
            return true;
        }

        // /auth コマンド
        if (!(sender instanceof Player p)) {
            sender.sendMessage("プレイヤー専用コマンドです。");
            return true;
        }
        UUID id = p.getUniqueId();

        if (!unauthed.contains(id)) {
            p.sendMessage("§aすでに認証済みです。");
            return true;
        }

        if (args.length != 1) {
            p.sendMessage("§c使い方: /auth <パスワード>");
            return true;
        }

        if (args[0].equals(password)) {
            authenticate(p);
        } else {
            p.sendMessage("§c✘ パスワードが違います。もう一度入力してください。");
        }
        return true;
    }
}
