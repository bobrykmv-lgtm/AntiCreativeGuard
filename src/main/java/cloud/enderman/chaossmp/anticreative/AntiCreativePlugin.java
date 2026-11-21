package cloud.enderman.chaossmp.anticreative;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class AntiCreativePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private LuckPerms luckPerms;
    private Set<String> allowedGroups = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        try {
            this.luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            getLogger().severe("LuckPerms не найден! Плагин отключается.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("anticreative") != null) {
            getCommand("anticreative").setExecutor(this);
        }

        getLogger().info("AntiCreativeGuard успешно запущен. Разрешенные группы: " + allowedGroups);
    }

    private void loadSettings() {
        reloadConfig();
        List<String> groups = getConfig().getStringList("allowed-groups");
        allowedGroups = groups.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private boolean isAllowed(Player player) {
        if (luckPerms == null) return false;
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return false;

        // Проверяем основную группу игрока
        if (allowedGroups.contains(user.getPrimaryGroup().toLowerCase())) {
            return true;
        }

        // Проверяем все унаследованные и дополнительные группы игрока
        return user.getNodes(NodeType.INHERITANCE).stream()
                .map(InheritanceNode::getGroupName)
                .map(String::toLowerCase)
                .anyMatch(allowedGroups::contains);
    }

    // 1. Блокируем саму смену режима на уровне ядра (срабатывает и на /gamemode, и на F3+F4, и на /op)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newMode = event.getNewGameMode();

        // Разрешаем возврат в выживание, чтобы не запереть игрока, если у него раньше был креатив
        if (newMode == GameMode.SURVIVAL) {
            return;
        }

        if (!isAllowed(player)) {
            event.setCancelled(true);
            sendMsg(player, "messages.no-permission");
        }
    }

    // 2. Блокируем ввод команд смены режима заранее, чтобы не выдавало лишних сообщений
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isAllowed(player)) return;

        String msg = event.getMessage().toLowerCase().trim();
        String cmd = msg.split(" ")[0];

        if (cmd.equals("/gamemode") || cmd.equals("/gm")
                || cmd.equals("/minecraft:gamemode") || cmd.equals("/essentials:gamemode")
                || cmd.equals("/essentials:gm") || cmd.equals("/gmc")
                || cmd.equals("/gms") || cmd.equals("/gma") || cmd.equals("/gmsp")) {
            event.setCancelled(true);
            sendMsg(player, "messages.no-permission");
        }
    }

    // 3. Страховка при входе на сервер: если игрок зашёл в креативе/спектаторе без прав — сбрасываем в выживание
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && !isAllowed(player)) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("anticreative.admin")) {
            sender.sendMessage(color(getConfig().getString("messages.only-admin", "&cНет прав.")));
            return true;
        }

        loadSettings();
        sender.sendMessage(color(getConfig().getString("messages.reloaded", "&aКонфиг перезагружен.")));
        return true;
    }

    private void sendMsg(Player player, String path) {
        String raw = getConfig().getString(path);
        if (raw != null && !raw.isEmpty()) {
            player.sendMessage(color(raw));
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
