package org.example.server.licensing;

import com.itextpdf.barcodes.BarcodeQRCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/remote-access")
public class RemoteAccessController {

    private final CloudflareTunnelService tunnelService;

    public RemoteAccessController(CloudflareTunnelService tunnelService) {
        this.tunnelService = tunnelService;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return tunnelService.getStatus();
    }

    @PostMapping("/start")
    public Map<String, Object> startTunnel(@RequestParam(defaultValue = "58080") int port) {
        return tunnelService.startTunnel(port);
    }

    @PostMapping("/stop")
    public Map<String, Object> stopTunnel() {
        return tunnelService.stopTunnel();
    }

    @GetMapping(value = "/qr-image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getLiveQrImage() {
        Map<String, Object> status = tunnelService.getStatus();
        String url = String.valueOf(status.getOrDefault("liveUrl", ""));
        if (url == null || url.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            BarcodeQRCode qrcode = new BarcodeQRCode(url);
            java.awt.Image awtImg = qrcode.createAwtImage(Color.BLACK, Color.WHITE);

            int size = 260;
            BufferedImage bufferedImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = bufferedImage.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2d.drawImage(awtImg, 0, 0, size, size, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "PNG", baos);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"tunnel-qr.png\"")
                    .contentType(MediaType.IMAGE_PNG)
                    .body(baos.toByteArray());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
