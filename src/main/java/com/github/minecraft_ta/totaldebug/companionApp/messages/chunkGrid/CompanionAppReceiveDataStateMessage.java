package com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ReceiveDataStateMessage;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

/**
 * Message from the companion app which signals if it wants to start or stop receiving chunk view data.
 */
public class CompanionAppReceiveDataStateMessage extends AbstractMessageIncoming {

    private boolean state;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.state = messageStream.readBoolean();
    }

    public static void handle(CompanionAppReceiveDataStateMessage message) {
        TotalDebug.INSTANCE.network.sendToServer(new ReceiveDataStateMessage(message.state));
    }
}
