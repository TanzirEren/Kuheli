package com.kuheli.app.ui;

import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import com.kuheli.app.R;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

/** One Markwon renderer per colour mode (light/dark), so code blocks always match the theme. */
public final class Md {
    private static final Markwon[] CACHE = new Markwon[2];

    private Md() {}

    public static synchronized Markwon get(Context c) {
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int slot = night ? 1 : 0;
        if (CACHE[slot] == null) {
            final int link = c.getColor(R.color.k_accent);
            final int codeBg = c.getColor(R.color.k_code_bg);
            final int codeText = c.getColor(R.color.k_text);
            final int line = c.getColor(R.color.k_line);
            Context app = c.getApplicationContext();
            CACHE[slot] = Markwon.builder(app)
                    .usePlugin(new AbstractMarkwonPlugin() {
                        @Override
                        public void configureTheme(@NonNull MarkwonTheme.Builder b) {
                            b.linkColor(link)
                                    .codeTextColor(codeText)
                                    .codeBackgroundColor(codeBg)
                                    .codeBlockTextColor(codeText)
                                    .codeBlockBackgroundColor(codeBg)
                                    .thematicBreakColor(line)
                                    .headingBreakColor(line)
                                    .headingTextSizeMultipliers(new float[]{1.6f, 1.4f, 1.25f, 1.12f, 1.05f, 1f});
                        }
                    })
                    .usePlugin(TablePlugin.create(app))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TaskListPlugin.create(app))
                    .usePlugin(LinkifyPlugin.create())
                    .build();
        }
        return CACHE[slot];
    }
}
