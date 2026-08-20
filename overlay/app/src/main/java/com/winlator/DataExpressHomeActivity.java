package com.winlator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/** Small Android-native launcher shown before and after the Win32 session. */
public class DataExpressHomeActivity extends Activity {
    private static final int OPEN_DATABASE_REQUEST = 1001;
    private static final int SAVE_DIAGNOSTICS_REQUEST = 1002;
    private static final String DIAGNOSTICS_OFFERED_EXTRA = "dataexpress_diagnostics_offered";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dataexpress_home);

        findViewById(R.id.openDemoButton).setOnClickListener(view -> openDemo());
        findViewById(R.id.openDatabaseButton).setOnClickListener(view -> pickDatabase());
        findViewById(R.id.shareDiagnosticsButton).setOnClickListener(
            view -> DataExpressDiagnostics.share(this));
        findViewById(R.id.saveDiagnosticsButton).setOnClickListener(view -> saveDiagnostics());
        findViewById(R.id.copyDiagnosticsButton).setOnClickListener(
            view -> DataExpressDiagnostics.copyToClipboard(this));
        findViewById(R.id.aboutProjectButton).setOnClickListener(view -> showAbout());
        findViewById(R.id.closeApplicationButton).setOnClickListener(view -> {
            DataExpressBootstrap.stopWineProcesses(this);
            finishAndRemoveTask();
        });
        updateLastResult(getIntent());
        offerDiagnosticsAfterFailure(getIntent());
        if (savedInstanceState == null) handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        updateLastResult(intent);
        offerDiagnosticsAfterFailure(intent);
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

    private void saveDiagnostics() {
        if (!DataExpressDiagnostics.hasEvents(this)) {
            Toast.makeText(this, "Журнал диагностики пока пуст.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/x-ndjson");
        create.putExtra(Intent.EXTRA_TITLE, DataExpressDiagnostics.suggestedFilename());
        startActivityForResult(create, SAVE_DIAGNOSTICS_REQUEST);
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
        if (requestCode == SAVE_DIAGNOSTICS_REQUEST) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try {
                DataExpressDiagnostics.exportToUri(this, data.getData());
                Toast.makeText(this, "Файл журнала сохранён.", Toast.LENGTH_LONG).show();
                DataExpressDiagnostics.share(this);
            }
            catch (Exception error) {
                Toast.makeText(this, "Не удалось сохранить журнал: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != OPEN_DATABASE_REQUEST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        launchDatabase(uri, data.getFlags());
    }

    private void offerDiagnosticsAfterFailure(Intent intent) {
        if (intent == null || intent.getBooleanExtra(DIAGNOSTICS_OFFERED_EXTRA, false)) return;
        String result = intent.getStringExtra(DataExpressBootstrap.LAST_RESULT_EXTRA);
        if (result == null) return;
        String lower = result.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("ошиб") && !lower.contains("не удалось") && !lower.contains("код 137")) return;
        intent.putExtra(DIAGNOSTICS_OFFERED_EXTRA, true);
        getWindow().getDecorView().post(() -> DataExpressDiagnostics.share(this));
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
            .setTitle("О проекте")
            .setMessage("DataExpress Android запускает настольный DataExpress и расширения EPAS внутри Wine/Box64.\n\n"
                + "Основано на открытых проектах DataExpress и Winlator. Это экспериментальная учебная сборка; перед работой с важной базой создавайте резервную копию.\n\n"
                + "Диагностика записывает технические события запуска. Содержимое записей и пароли в журнал не включаются. Журнал можно скопировать, сохранить или вручную отправить через системное меню Android.")
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
