package com.winlator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

/** Small Android-native launcher shown before and after the Win32 session. */
public class DataExpressHomeActivity extends Activity {
    private static final int OPEN_DATABASE_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dataexpress_home);

        findViewById(R.id.openDemoButton).setOnClickListener(view -> openDemo());
        findViewById(R.id.openDatabaseButton).setOnClickListener(view -> pickDatabase());
        findViewById(R.id.aboutProjectButton).setOnClickListener(view -> showAbout());
        findViewById(R.id.closeApplicationButton).setOnClickListener(view -> {
            DataExpressBootstrap.stopWineProcesses(this);
            finishAndRemoveTask();
        });
        updateLastResult(getIntent());
        if (savedInstanceState == null) handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        updateLastResult(intent);
        handleLaunchIntent(intent);
    }

    private void openDemo() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(DataExpressBootstrap.ACTION_LAUNCH_DEMO);
        startActivity(intent);
    }

    private void pickDatabase() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, OPEN_DATABASE_REQUEST);
    }

    private void handleLaunchIntent(Intent source) {
        if (source == null) return;
        if (Intent.ACTION_VIEW.equals(source.getAction()) && source.getData() != null) {
            launchDatabase(source.getData(), source.getFlags());
        }
        else if (DataExpressBootstrap.ACTION_OPEN_DATABASE.equals(source.getAction())) {
            pickDatabase();
        }
    }

    private void launchDatabase(Uri uri, int flags) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri, this, MainActivity.class);
        intent.addFlags(flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OPEN_DATABASE_REQUEST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        launchDatabase(uri, data.getFlags());
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
            .setTitle("О проекте")
            .setMessage("DataExpress Android запускает настольный DataExpress и расширения EPAS внутри Wine/Box64.\n\n"
                + "Основано на открытых проектах DataExpress и Winlator. Это экспериментальная учебная сборка; перед работой с важной базой создавайте резервную копию.\n\n"
                + "Диагностика записывает технические события запуска. Содержимое записей и пароли в журнал не включаются.")
            .setPositiveButton("Закрыть", null)
            .show();
    }

    private void updateLastResult(Intent intent) {
        TextView status = findViewById(R.id.lastSessionText);
        String result = intent.getStringExtra(DataExpressBootstrap.LAST_RESULT_EXTRA);
        status.setText(result == null || result.isEmpty()
            ? "Готово к запуску. Выберите учебную или внешнюю базу."
            : result);
    }
}
