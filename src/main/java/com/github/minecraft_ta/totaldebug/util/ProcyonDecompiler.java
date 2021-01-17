package com.github.minecraft_ta.totaldebug.util;

import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.assembler.metadata.MetadataSystem;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import com.strobel.decompiler.languages.java.JavaFormattingOptions;

import java.io.StringWriter;

public class ProcyonDecompiler {

    public static String decompile(String name) {
        ITypeLoader loader = (internalName, buffer) -> {
            if(internalName.endsWith(".class"))
                internalName = internalName.substring(0, internalName.length() - 6);

            try {
                byte[] code = BytecodeUtil.getRemappedClass(Class.forName(internalName)).toByteArray();
                if(code == null)
                    return false;

                buffer.position(0);
                buffer.putByteArray(code, 0, code.length);
                buffer.position(0);
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        };

        DecompilerSettings settings = new DecompilerSettings();
        settings.setUnicodeOutputEnabled(false);
        settings.setShowSyntheticMembers(true);
        settings.setTypeLoader(loader);
        settings.setJavaFormattingOptions(JavaFormattingOptions.createDefault());

        MetadataSystem system = new MetadataSystem(loader);
        system.setEagerMethodLoadingEnabled(true);

        DecompilationOptions decompilationOptions = new DecompilationOptions();
        decompilationOptions.setSettings(settings);
        decompilationOptions.setFullDecompilation(true);

        StringWriter writer = new StringWriter();
        settings.getLanguage().decompileType(system.lookupType(name).resolve(), new PlainTextOutput(writer), decompilationOptions);
        return writer.toString();
    }
}