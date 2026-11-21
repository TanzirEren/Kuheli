package com.kuheli.app.ui;

import android.os.Bundle;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.kuheli.app.BuildConfig;
import com.kuheli.app.R;
import com.kuheli.app.core.Native;

public class AboutActivity extends BaseActivity {
    private static final String BODY = "**Kuheli** is a chat app for any OpenAI-compatible provider, with skills "
            + "and optional reverse-engineering tools from REA.\n\n"
            + "**Built with**\n\n- Java and XML for the interface\n- Python for provider routing and the tool loop\n"
            + "- C++ for the native layer\n- Node.js for the REA bridge\n\n"
            + "**REA** is an independent open source project (MIT) by its own authors. Get it only from "
            + "github.com/morluto/rea, rea.tools or the rea-agents package on npm. Copies with similar names "
            + "have carried malware.\n\n"
            + "**Use it lawfully.** REA is meant for software you own or are authorised to examine, and for "
            + "research, education, interoperability and preservation. Tool calls ask for your approval by default. "
            + "REA is not a sandbox, so do not open untrusted files with it on a device you care about.\n\n"
            + "**Privacy.** Chats and skills stay on this device. Your messages and any tool output you allow go "
            + "to the provider you chose. API keys are encrypted with the Android Keystore and are not included "
            + "in backups.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        setupToolbar(tb, true);
        tb.setTitle("About");
        ((TextView) findViewById(R.id.about_version)).setText("Version " + BuildConfig.VERSION_NAME
                + "\nNative layer: " + (Native.isLoaded() ? "loaded" : "not loaded")
                + "\nEmbedded Node.js: " + (Native.hasNode() ? "included" : "not included in this build"));
        Md.get(this).setMarkdown(findViewById(R.id.about_body), BODY);
    }
}
