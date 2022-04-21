import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.awt.image.BufferedImage;
import java.io.File;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;


import javax.imageio.ImageIO;

public class Bot extends TelegramLongPollingBot {

    private static String instructions;
    static {
        try {
            instructions = new String(Bot.class.getClassLoader().getResourceAsStream("data/instructions").readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private final Logger logger = LoggerFactory.getLogger(Bot.class);

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
            logger.warn("no message received");
            return;
        }

        Message message = update.getMessage();

        try {
            if (message.hasText()) {
                switch (message.getText()) {
                    case "/start":
                    case "/help":
                        reply(message, instructions);
                        break;
                    default:
                        logger.warn("unknown command: " + message.toString());
                        reply(message, "Я не знаю, что с этим делать. Для подсказки пришли команду /help");
                        break;
                }
                return;
            }

            if (message.hasDocument()) {
                Document sourceImage = message.getDocument();
                if (!sourceImage.getMimeType().contains("image")) {
                    logger.warn("unsupported mime type: " + sourceImage.getMimeType());
                    reply(message, "Я не умею работать с " + sourceImage.getMimeType());
                    return;
                }

                String caption = message.getCaption();
                if (caption == null) {
                    reply(message, "Необходимо указать параметры обработки, для помощи используй /help");
                    return;
                }

                String[] scalingOptions = caption.split(" ");
                File original = downloadDocument(sourceImage);
                File resized = resizeImage(scalingOptions, original, sourceImage.getFileName(), sourceImage.getMimeType().split("/")[1]);

                execute(SendDocument.builder()
                        .chatId(String.valueOf(update.getMessage().getChatId()))
                        .document(new InputFile(resized))
                        .caption("готово!")
                        .build()
                );

                resized.delete();
                return;
            }

            reply(update.getMessage(), "Это я не умею. /help");

        } catch (TelegramApiException | IOException e) {
            logger.error(e.getMessage(), e);
            reply(message, "Я немного сломался, попробуй что-нибудь ещё сделать.");
        } catch (IllegalArgumentException e) {
            reply(message, "Ошибка в параметрах для масштабирования /help");
        }

    }

    private File downloadDocument(Document doc) throws TelegramApiException {
        GetFile request = GetFile.builder().fileId(doc.getFileId()).build();
        String path = execute(request).getFilePath();
        return downloadFile(path);
    }

    private File resizeImage(String[] scalingOptions, File input, String fileName, String format) throws TelegramApiException, IllegalArgumentException, IOException {
        BufferedImage inputImage = ImageIO.read(input);
        int newWidth, newHeight;

        if (scalingOptions.length == 2) {
            newWidth = Integer.parseInt(scalingOptions[0]);
            newHeight = Integer.parseInt(scalingOptions[1]);
        } else if (scalingOptions.length == 1) {
            if (scalingOptions[0].contains("%")) {
                int scale = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                newWidth = (int) (inputImage.getWidth() * (scale / 100.));
                newHeight = (int) (inputImage.getHeight() * (scale / 100.));
            } else {
                newWidth = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                newHeight = (int) (((double) newWidth / inputImage.getWidth()) * (double) inputImage.getHeight());
            }
        } else {
            throw new IllegalArgumentException("слишком много параметров");
        }

        BufferedImage resizedImage = Scalr.resize(inputImage, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.FIT_EXACT, newWidth, newHeight, Scalr.OP_ANTIALIAS);
        File result = new File("resized_" + fileName);
        ImageIO.write(resizedImage, format, result);
        return result;
    }

    private void reply(Message m, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(String.valueOf(m.getChatId()))
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            logger.error(e.getMessage(), e);
        }

    }
}