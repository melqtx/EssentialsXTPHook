package com.plushycat.essentialsxtphook;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.ess3.api.events.TPARequestEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class essentialstphook extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Essentials essentials;
    // key: target:requester, value: expiry timestamp (ms)
    private final Map<String, Long> activeRequests = new HashMap<>();
    private long REQUEST_EXPIRY_MS;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        essentials = (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentials == null) {
            getLogger().severe("EssentialsX not found! This plugin is required.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        int expirySeconds = getConfig().getInt("expiry-delay", 120);
        REQUEST_EXPIRY_MS = expirySeconds * 1000L;

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("EssentialsXTP has been enabled and hooked into EssentialsX.");
    }

    @Override
    public void onDisable() {
        getLogger().info("EssentialsXTP has been disabled.");
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String command = message.toLowerCase();
        FileConfiguration config = getConfig();

        if (command.startsWith("/tpa ") || command.startsWith("/tpahere ")) {
            String[] args = message.split(" ");
            if (args.length < 2) return;

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !target.isOnline()) return;

            boolean teleportHere = command.startsWith("/tpahere ");
            if (!player.hasPermission(teleportHere ? "essentials.tpahere" : "essentials.tpa")) {
                return;
            }

            User requesterUser = essentials.getUser(player);
            User targetUser = essentials.getUser(target);
            if (requesterUser == null || targetUser == null) return;
            if (player.getName().equalsIgnoreCase(target.getName())) return;
            if (targetUser.hasOutstandingTpaRequest(player.getName(), teleportHere)) return;

            TPARequestEvent requestEvent = new TPARequestEvent(requesterUser.getSource(), targetUser, teleportHere);
            Bukkit.getPluginManager().callEvent(requestEvent);
            if (requestEvent.isCancelled()) return;

            event.setCancelled(true);
            targetUser.requestTeleport(requesterUser, teleportHere);

            String key = requestKey(target.getName(), player.getName());
            activeRequests.put(key, System.currentTimeMillis() + REQUEST_EXPIRY_MS);

            Bukkit.getScheduler().runTask(this, () -> {
                String msgKey = teleportHere ? "messages.target-request-tpahere" : "messages.target-request";
                String rawMsg = config.getString(msgKey, player.getName() + " has requested to teleport to you.");
                String formattedMsg = rawMsg
                        .replace("{requester}", player.getName())
                        .replace("{target}", target.getName());

                playSound(target, config.getString("sounds.request"));

                Component accept = Component.text("Accept", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/tpaccept " + player.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text(config.getString("messages.accept-hover", "Accept teleport"))));

                Component deny = Component.text("Deny", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/tpdeny " + player.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text(config.getString("messages.deny-hover", "Deny teleport"))));

                target.sendMessage(LEGACY.deserialize(formattedMsg)
                        .append(Component.text(" "))
                        .append(Component.text("[", NamedTextColor.GRAY))
                        .append(accept)
                        .append(Component.text("] [", NamedTextColor.GRAY))
                        .append(deny)
                        .append(Component.text("]", NamedTextColor.GRAY)));

                String requesterMsgRaw = config.getString("messages.requester-notification", "You sent a teleport request to {target}.");
                String requesterMsg = requesterMsgRaw
                        .replace("{target}", target.getName())
                        .replace("{requester}", player.getName());

                Component cancel = Component.text("[✘]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/tpacancel " + target.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text(config.getString("messages.cancel-hover", "Cancel request"))));

                player.sendMessage(LEGACY.deserialize(requesterMsg).append(Component.text(" ")).append(cancel));
            });
        }

        else if (command.startsWith("/tpaccept ")) {
            String[] args = message.split(" ");
            if (args.length < 2) {
                playSound(player, config.getString("sounds.accept"));
                return;
            }
            Player sender = Bukkit.getPlayerExact(args[1]);
            String key = requestKey(player.getName(), args[1]);
            if (!isRequestActive(key)) {
                sendExpiredFeedback(player, sender, config);
                return;
            }
            activeRequests.remove(key);
            if (sender != null && sender.isOnline()) {
                playSound(sender, config.getString("sounds.accept"));
            }
            playSound(player, config.getString("sounds.accept"));
        }

        else if (command.startsWith("/tpdeny ")) {
            String[] args = message.split(" ");
            if (args.length < 2) {
                playSound(player, config.getString("sounds.deny"));
                return;
            }
            Player sender = Bukkit.getPlayerExact(args[1]);
            String key = requestKey(player.getName(), args[1]);
            if (!isRequestActive(key)) {
                sendExpiredFeedback(player, sender, config);
                return;
            }
            activeRequests.remove(key);
            if (sender != null && sender.isOnline()) {
                playSound(sender, config.getString("sounds.deny"));
            }
            playSound(player, config.getString("sounds.deny"));
        }

        else if (command.startsWith("/tpacancel")) {
            String[] args = message.split(" ");
            String targetName = args.length > 1 ? args[1] : null;
            String key = targetName != null ? requestKey(targetName, player.getName()) : null;
            Player target = targetName != null ? Bukkit.getPlayerExact(targetName) : null;
            if (key == null || !isRequestActive(key)) {
                sendExpiredFeedback(player, target, config);
                return;
            }
            activeRequests.remove(key);
            playSound(player, config.getString("sounds.cancel"));
        }
    }

    private String requestKey(String targetName, String requesterName) {
        return targetName.toLowerCase(Locale.ROOT) + ":" + requesterName.toLowerCase(Locale.ROOT);
    }

    private boolean isRequestActive(String key) {
        Long expiry = activeRequests.get(key);
        return expiry != null && System.currentTimeMillis() < expiry;
    }

    private void sendExpiredFeedback(Player receiver, Player sender, FileConfiguration config) {
        String expiredMsg = config.getString("messages.expired-request", "&cThis teleport request has expired.");
        playSound(receiver, config.getString("sounds.expired"));
        receiver.sendMessage(LEGACY.deserialize(expiredMsg));
        if (sender != null && sender.isOnline()) {
            playSound(sender, config.getString("sounds.expired"));
            sender.sendMessage(LEGACY.deserialize(expiredMsg));
        }
    }

    private void playSound(Player player, String soundName) {
        if (soundName == null || soundName.isEmpty()) return;
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid sound name: " + soundName);
        }
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("essxtpreload")) {
            reloadConfig();
            sender.sendMessage(Component.text("[EssentialsXTP] Config reloaded!", NamedTextColor.GREEN));
            return true;
        }
        return false;
    }
}
