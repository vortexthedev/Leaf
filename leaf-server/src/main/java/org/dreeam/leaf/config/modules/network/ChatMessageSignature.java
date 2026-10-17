package org.dreeam.leaf.config.modules.network;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class ChatMessageSignature extends ConfigModule {

    public String basePath() {
        return ConfigCategory.NETWORK.basePath();
    }

    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        enabled = globalConfig.getBoolean(basePath() + ".chat-message-signature", enabled, """
                Whether or not enable chat message signature,
                disable will prevent players to report chat messages.
                And also disables the popup when joining a server without
                'secure chat', such as offline-mode servers.""");
    }
}
