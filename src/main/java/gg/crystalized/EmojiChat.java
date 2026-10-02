package gg.crystalized;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;

import java.util.Map;

// Replaces :shortcode: tokens in player chat with emoji, proxy-wide so it
// behaves the same on every backend server. Messages without any shortcode
// are left untouched (keeping their chat signature intact).
public class EmojiChat {

    // shortcode -> emoji, written as U+ escapes so file encoding can never corrupt them
    private static final Map<String, String> EMOJI = Map.of(
            "party", "\uD83C\uDF89", // 🎉 U+1F389
            "zany", "\uD83E\uDD2A", // 🤪 U+1F92A
            "gg", "\uD83C\uDFC6", // 🏆 U+1F3C6
            "rip", "\u26B0\uFE0F", // ⚰️ U+26B0
            "heart", "\u2764\uFE0F", // ❤️ U+2764
            "skull", "\uD83D\uDC80", // 💀 U+1F480
            "star", "\u2B50" // ⭐ U+2B50
    );

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        String message = e.getMessage();
        String replaced = message;
        for (Map.Entry<String, String> entry : EMOJI.entrySet()) {
            replaced = replaced.replace(":" + entry.getKey() + ":", entry.getValue());
        }
        if (!replaced.equals(message)) {
            e.setResult(PlayerChatEvent.ChatResult.message(replaced));
        }
    }
}
