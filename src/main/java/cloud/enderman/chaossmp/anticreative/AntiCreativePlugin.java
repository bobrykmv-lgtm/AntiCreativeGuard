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
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
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

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "/gamemode", "/gm", "/minecraft:gamemode", "/essentials:gamemode",
            "/essentials:gm", "/gmc", "/gms", "/gma", "/gmsp",
            "/give", "/minecraft:give", "/essentials:give",
            "/i", "/item", "/essentials:i", "/essentials:item"
    );

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

    // 1. Блокируем смену режима на уровне ядра (/gamemode, F3+F4, сторонние плагины)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (event.getNewGameMode() == GameMode.SURVIVAL) {
            return;
        }

        if (!isAllowed(player)) {
            event.setCancelled(true);
            sendMsg(player, "messages.no-permission");
        }
    }

    // 2. Блокируем взятие предметов из креативного инвентаря на уровне пакетов
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player player && !isAllowed(player)) {
            event.setCancelled(true);
            player.setGameMode(GameMode.SURVIVAL);
            sendMsg(player, "messages.no-permission");
        }
    }

    // 3. Блокируем команды смены режима, /give и попытки обхода через /execute
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isAllowed(player)) return;

        String msg = event.getMessage().toLowerCase().trim();
        String[] parts = msg.split("\\s+");
        String cmd = parts[0];

        if (BLOCKED_COMMANDS.contains(cmd)) {
            event.setCancelled(true);
            sendMsg(player, "messages.no-permission");
            return;
        }

        // Защита от обхода через /execute ... run give / gamemode
        if ((cmd.equals("/execute") || cmd.equals("/minecraft:execute"))
                && (msg.contains(" give ") || msg.contains(" gamemode ") || msg.contains(" minecraft:give ") || msg.contains(" minecraft:gamemode "))) {
            event.setCancelled(true);
            sendMsg(player, "messages.no-permission");
        }
    }

    // 4. Скрываем команды /give и /gamemode из подсказок Tab-Complete для тех, кому запрещено
    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (!isAllowed(event.getPlayer())) {
            event.getCommands().removeIf(c -> {
                String lower = c.toLowerCase();
                return lower.equals("gamemode") || lower.equals("gm")
                        || lower.equals("minecraft:gamemode") || lower.equals("essentials:gamemode")
                        || lower.equals("gmc") || lower.equals("gms") || lower.equals("gma") || lower.equals("gmsp")
                        || lower.equals("give") || lower.equals("minecraft:give") || lower.equals("essentials:give")
                        || lower.equals("i") || lower.equals("item");
            });
        }
    }

    // 5. Страховка при входе на сервер
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
