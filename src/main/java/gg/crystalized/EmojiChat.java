package gg.crystalized;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;

import java.util.Map;

// Replaces :shortcode: tokens in player chat with emoji, proxy-wide so it
// behaves the same on every backend server. Messages without any shortcode
// are left untouched (keeping their chat signature intact).
public class EmojiChat {

    // shortcode -> emoji, written as U+ escapes so file encoding can never corrupt them
    private static final Map<String, String> EMOJI = Map.ofEntries(
            Map.entry("party", "\uD83C\uDF89"), // 🎉 U+1F389
            Map.entry("zany", "\uD83E\uDD2A"), // 🤪 U+1F92A
            Map.entry("gg", "\uD83C\uDFC6"), // 🏆 U+1F3C6
            Map.entry("rip", "\u26B0\uFE0F"), // ⚰️ U+26B0
            Map.entry("heart", "\u2764\uFE0F"), // ❤️ U+2764
            Map.entry("skull", "\uD83D\uDC80"), // 💀 U+1F480
            Map.entry("star", "\u2B50"), // ⭐ U+2B50
            Map.entry("sob", "\uD83D\uDE2D"), // 😭 U+1F62D
            Map.entry("pray", "\uD83D\uDE4F"), // 🙏 U+1F64F
            Map.entry("fire", "\uD83D\uDD25"), // 🔥 U+1F525
            Map.entry("eyes", "\uD83D\uDC40"), // 👀 U+1F440
            Map.entry("thumbsup", "\uD83D\uDC4D"), // 👍 U+1F44D
            Map.entry("thumbsdown", "\uD83D\uDC4E"), // 👎 U+1F44E
            Map.entry("check", "\u2705"), // ✅ U+2705
            Map.entry("cross", "\u274C"), // ❌ U+274C
            Map.entry("thinking", "\uD83E\uDD14"), // 🤔 U+1F914
            Map.entry("wave", "\uD83D\uDC4B"), // 👋 U+1F44B
            Map.entry("cool", "\uD83D\uDE0E") // 😎 U+1F60E
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
