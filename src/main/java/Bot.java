import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class Bot extends TelegramLongPollingBot {

    private final ForkJoinPool pool = new ForkJoinPool();

    private final Map<String, ImageResizerRecursiveTask> chatsWithWorkingTasks = new HashMap<>();

    private final Pattern batchPattern = Pattern.compile("/batch ((\\d+( \\d+)?)|(\\d+)%)$");

    private static String instructions;

    static {
        try {
            instructions = new String(Bot.class.getClassLoader().getResourceAsStream("data/instructions").readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public String getBotUsername() {
        return "";
    }

    @Override
    public String getBotToken() {
        return "";
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (!update.hasMessage()) {
            log.warn("no message received");
            return;
        }

        Message message = update.getMessage();
        String chatId = String.valueOf(message.getChatId());

        try {
            if (message.hasText()) {
                String command = message.getText();
                switch (command) {
                    case "/start":
                    case "/help":
                        reply(chatId, instructions);
                        break;
                    case "/processBatch":
                        sendBackImages(chatId);
                        log.debug("done with chat " + chatId);
                        break;
                    default:
                        Matcher matcher = batchPattern.matcher(command);
                        if (matcher.matches()) {
                            String[] scalingOptions = matcher.group(1).split(" ");
                            addTask(chatId, scalingOptions);
                            log.debug("created batch task for chat " + chatId);
                            break;
                        }
                        log.warn("unknown command: " + message.toString());
                        reply(chatId, "Я не знаю, что с этим делать. Для подсказки пришли команду /help");
                        break;
                }
                return;
            }

            if (message.hasDocument()) {
                Document sourceImage = message.getDocument();
                if (!sourceImage.getMimeType().contains("image")) {
                    log.warn("unsupported mime type: " + sourceImage.getMimeType());
                    reply(chatId, "Я не умею работать с " + sourceImage.getMimeType());
                    return;
                }

                String caption = message.getCaption();
                if (!chatsWithWorkingTasks.containsKey(chatId) && caption == null) {
                    reply(chatId, "Необходимо указать параметры обработки, для помощи используй /help");
                    return;
                }

                File original = downloadDocument(sourceImage);
                log.debug("received document for processing from chat " + chatId);

                if(!chatsWithWorkingTasks.containsKey(chatId)) {
                    String[] scalingOptions = caption.split(" ");
                    addTask(chatId, scalingOptions);
                    addImageToTask(chatId, original, sourceImage.getFileName(), sourceImage.getMimeType().split("/")[1]);
                    log.debug("task created for chat " + chatId);
                    sendBackImages(chatId);
                    log.debug("done with chat " + chatId);
                } else {
                    addImageToTask(chatId, original, sourceImage.getFileName(), sourceImage.getMimeType().split("/")[1]);
                    log.debug("image added for chat " + chatId);
                }
                return;
            }

            reply(chatId, "Это я не умею. /help");

        } catch (TelegramApiException | IOException e) {
            log.error(e.getMessage(), e);
            reply(chatId, "Я немного сломался, попробуй что-нибудь ещё сделать.");
        } catch (IllegalArgumentException e) {
            reply(chatId, "Ошибка в параметрах для масштабирования /help");
        } catch (ExecutionException | InterruptedException e) {
            log.error(e.getMessage(), e);
        }

    }

    private void addImageToTask(String chatId, File original, String filename, String mimeType) throws IOException {
        ImageResizerRecursiveTask task = chatsWithWorkingTasks.get(chatId);
        ImageToResize imageToResize = new ImageToResize(task.getScalingOptions(), original, filename, mimeType);
        task.addImage(imageToResize);
    }

    private File downloadDocument(Document doc) throws TelegramApiException {
        GetFile request = GetFile.builder().fileId(doc.getFileId()).build();
        String path = execute(request).getFilePath();
        return downloadFile(path);
    }

    private void sendBackImages(String chatId) throws TelegramApiException, IOException, ExecutionException, InterruptedException {
        ImageResizerRecursiveTask task = chatsWithWorkingTasks.get(chatId);
        log.debug("task launching for chat " + chatId);
        pool.execute(task);
        log.debug("ready to get results for chat " + chatId);
        List<File> imagesToSend = task.get();
        log.debug("preparing to send images back to chat " + chatId);
        for (File f : imagesToSend) {
            execute(SendDocument.builder()
                    .caption("Готово")
                    .chatId(chatId)
                    .document(new InputFile(f))
                    .build());
        }
        imagesToSend.forEach(File::delete);
        Files.deleteIfExists(Path.of(chatId));
        chatsWithWorkingTasks.remove(chatId);
    }

    private void reply(String chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error(e.getMessage(), e);
        }

    }

    private void addTask(String chatId, String[] scalingOptions) throws IllegalArgumentException, IOException {
        if (chatsWithWorkingTasks.containsKey(chatId)) {
            throw new IllegalArgumentException("Задача для чата " + chatId + " уже создана");
        }
        chatsWithWorkingTasks.put(chatId, new ImageResizerRecursiveTask(scalingOptions, chatId));
    }
}