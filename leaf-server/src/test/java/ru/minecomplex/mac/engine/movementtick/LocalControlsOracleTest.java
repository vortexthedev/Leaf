package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.player.state.PlayerInput;

/** Real mapping/mouse decisions for both resolved native restore-key quirks, including rebound and unknown keys. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalControlsOracleTest {

    @Test
    void matchesVanilla()throws Exception {
        for(String suffix:new String[]{"","-no-poll"}) {
            var raw=getClass().getResourceAsStream("/vanilla/777/local-controls"+suffix+""+".bin.gz");assertNotNull(raw);
            try(var in=new DataInputStream(new GZIPInputStream(raw))) {
                assertEquals(0x4d41434f,in.readInt());assertEquals(1,in.readUnsignedShort());assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL,in.readInt());
                boolean restore=in.readBoolean();assertEquals(suffix.isEmpty(),restore);assertEquals(128,in.readInt());
                for(int s=0;s<128;s++) {
                    int keyboard=in.readInt(),pollable=in.readInt();assertEquals(64,in.readInt());
                    var controls=new LocalPlayerControls();
                    for(int t=0;t<64;t++) {
                        var options=new LocalPlayerControls.Options(in.readBoolean(),in.readBoolean(),keyboard,restore,pollable);
                        controls.windowFocused=in.readBoolean();
                        int op=in.readInt(),pressedKey=in.readInt();boolean pressed=in.readBoolean();int physical=in.readInt();
                        if(op==0||op==1||op==7)controls.keyEvent(pressedKey,pressed,options);
                        else if(op==2)controls.screenOpened(options);
                        else if(op==3)controls.screenClosed(options,true,physical);
                        else if(op==4)controls.grabMouse(options,physical);
                        else if(op==5)controls.resetToggleKeys();
                        else controls.releaseAll(options);
                        String context="26.3"+suffix+" controls "+s+" tick "+t;
                        assertEquals(PlayerInput.fromMask(in.readInt()),controls.keys(),context);
                        assertEquals(in.readBoolean(),controls.mouseGrabbed,context+" grab");
                        var fork=controls.copy();controls.keyEvent(0,true,options);assertNotSame(controls,fork);
                        controls=fork;
                    }
                }
                assertEquals(-1,in.read());
            }
        }
    }
}
