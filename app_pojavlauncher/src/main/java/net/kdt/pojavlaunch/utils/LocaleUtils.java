package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * Keeps the existing context-wrapping call sites while allowing Android to resolve
 * the launcher locale from the system configuration. English lives in unqualified
 * resources and is therefore the fallback; Turkish is provided through values-tr.
 */
public class LocaleUtils extends ContextWrapper {

    public LocaleUtils(Context base) {
        super(base);
    }

    public static ContextWrapper setLocale(Context context) {
        return new LocaleUtils(context);
    }
}
