package ax.nk.noteblock.game.timeline.ui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reliable, zero-GUI text prompt fallback using chat.
 *
 * Usage:
 * - call open(player, prompt, onComplete)
 * - player types in chat
 * - type "cancel" to abort
 */
public final class ChatPrompt implements Listener {

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    private record Pending(Consumer<String> onComplete, Consumer<Void> onCancel) {
    }

    public ChatPrompt(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        pending.clear();
    }

    public boolean open(Player player, String prompt, Consumer<String> onComplete, Runnable onCancel) {
        if (pending.containsKey(player.getUniqueId())) return false;

        pending.put(player.getUniqueId(), new Pending(onComplete, (v) -> {
            if (onCancel != null) onCancel.run();
        }));

        player.closeInventory();
        player.sendMessage(ChatColor.GOLD + "Enter name in chat" + ChatColor.GRAY + " (type 'cancel' to abort)");
        if (prompt != null && !prompt.isBlank()) {
            player.sendMessage(ChatColor.GRAY + prompt);
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.6f, 1.2f);
        return true;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        final Pending p = pending.get(player.getUniqueId());
        if (p == null) return;

        event.setCancelled(true);
        final String msg = event.getMessage();

        if (msg == null) return;
        if (msg.equalsIgnoreCase("cancel")) {
            pending.remove(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.GRAY + "Cancelled.");
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.5f, 0.8f);
                p.onCancel().accept(null);
            });
            return;
        }

        pending.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> p.onComplete().accept(msg));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}

