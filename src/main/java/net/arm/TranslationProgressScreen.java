package net.arm;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class TranslationProgressScreen extends Screen {
    private final Screen parent;
    private final List<String> logLines = new ArrayList<>();

    private static final String[] SUPPORTED_LANGS = {
            "ru", "en", "de", "fr", "es", "it", "pt", "pl",
            "tr", "ja", "ko", "zh", "nl", "sv", "cs", "uk"
    };
    private int currentLangIndex = 0;

    private float progress = 0.0f;
    private String currentStatus = "Ready";

    private boolean isFinished = true;
    private boolean isLoading = false;

    private Button backButton;
    private Button applyButton;
    private Button downloadButton;
    private Button langButton;

    public TranslationProgressScreen(Screen parent) {
        super(Component.literal("Translation Control Center"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonWidth = 80;
        int spacing = 5;
        int totalWidth = (buttonWidth * 4) + (spacing * 3);
        int startX = (this.width - totalWidth) / 2;
        int y = this.height - 30;

        this.langButton = this.addRenderableWidget(Button.builder(
                Component.literal("Lang: " + SUPPORTED_LANGS[currentLangIndex].toUpperCase()),
                button -> {
                    currentLangIndex = (currentLangIndex + 1) % SUPPORTED_LANGS.length;
                    button.setMessage(Component.literal("Lang: " + SUPPORTED_LANGS[currentLangIndex].toUpperCase()));
                }
        ).bounds(startX, y, buttonWidth, 20).build());

        this.applyButton = this.addRenderableWidget(Button.builder(Component.literal("Apply"),
                        button -> {
                            String selectedLang = SUPPORTED_LANGS[currentLangIndex];

                            TextDumper.setTargetLang(selectedLang);

                            String vanillaCode = selectedLang.equals("en") ? "en_us" : selectedLang + "_" + selectedLang;
                            if (selectedLang.equals("pt")) vanillaCode = "pt_br";
                            if (selectedLang.equals("zh")) vanillaCode = "zh_cn";
                            if (selectedLang.equals("ja")) vanillaCode = "ja_jp";
                            if (selectedLang.equals("ko")) vanillaCode = "ko_kr";
                            if (selectedLang.equals("sv")) vanillaCode = "sv_se";
                            if (selectedLang.equals("cs")) vanillaCode = "cs_cz";
                            if (selectedLang.equals("uk")) vanillaCode = "uk_ua";

                            this.addLog("Switching Minecraft language to " + vanillaCode.toUpperCase() + "...");

                            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                            mc.options.languageCode = vanillaCode;
                            mc.getLanguageManager().setSelected(vanillaCode);
                            mc.options.save();

                            mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());

                            TextDumper.injectTranslations();

                            this.rebuildWidgets();
                            this.addLog("Successfully applied!");
                        })
                .bounds(startX + buttonWidth + spacing, y, buttonWidth, 20).build());

        this.downloadButton = this.addRenderableWidget(Button.builder(Component.literal("Download"),
                        button -> startDownload())
                .bounds(startX + (buttonWidth + spacing) * 2, y, buttonWidth, 20).build());

        this.backButton = this.addRenderableWidget(Button.builder(Component.literal("Back"),
                        button -> this.minecraft.setScreen(this.parent))
                .bounds(startX + (buttonWidth + spacing) * 3, y, buttonWidth, 20).build());

        updateButtonStates();
    }

    private void startDownload() {
        this.isLoading = true;
        this.isFinished = false;
        this.logLines.clear();
        updateButtonStates();

        TextDumper.deleteOldCache();
        this.addLog("Old cache cleared. Starting fresh download...");

        TextDumper.runDownloadAndTranslationAsync(this, SUPPORTED_LANGS[currentLangIndex]);
    }

    private void updateButtonStates() {
        boolean canInteract = !isLoading;
        if (langButton != null) langButton.active = canInteract;
        if (applyButton != null) applyButton.active = canInteract;
        if (downloadButton != null) downloadButton.active = canInteract;
        if (backButton != null) backButton.active = canInteract;
    }

    public void markAsFinished() {
        this.isLoading = false;
        this.isFinished = true;
        this.currentStatus = "Finished!";
        updateButtonStates();
    }
    public void updateProgress(float progress, String status) {
        this.progress = progress;
        this.currentStatus = status;
    }

    public synchronized void addLog(String line) {
        this.logLines.add(line);
        if (this.logLines.size() > 12) this.logLines.remove(0);
    }
    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        graphics.nextStratum();

        graphics.centeredText(this.font, this.title.getString(), this.width / 2, 15, 0xFFFFFFFF);
        graphics.centeredText(this.font, "Status: " + this.currentStatus, this.width / 2, 30, 0xFFAAAAAA);

        int barWidth = 200;
        int barX = (this.width - barWidth) / 2;
        graphics.fill(barX, 45, barX + barWidth, 57, 0xFF444444);
        graphics.fill(barX, 45, barX + (int)(barWidth * progress), 57, 0xFF00AA00);

        List<String> copyLogs;
        synchronized (this) { copyLogs = new ArrayList<>(this.logLines); }

        int logY = 70;
        graphics.centeredText(this.font, "Operations Log:", this.width / 2, logY, 0xFF55FF55);
        logY += 15;

        for (String log : copyLogs) {
            graphics.text(this.font, log, 20, logY, 0xFFE0E0E0, true);
            logY += 12;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return isFinished; }
}