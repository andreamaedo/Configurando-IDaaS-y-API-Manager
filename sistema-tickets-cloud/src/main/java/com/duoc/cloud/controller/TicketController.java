package com.duoc.cloud.controller;

import com.duoc.cloud.dto.EstadisticasResponseDTO;
import com.duoc.cloud.dto.TicketRequestDTO;
import com.duoc.cloud.dto.TicketResponseDTO;
import com.duoc.cloud.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    private ResponseEntity<byte[]> pdf(byte[] bytes, String nombre) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", nombre);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    // Crear resumen de compra (genera el PDF)
    @PostMapping("/generar")
    public ResponseEntity<TicketResponseDTO> generarTicket(@Valid @RequestBody TicketRequestDTO requestDTO) throws IOException {
        TicketResponseDTO respuesta = ticketService.generarTicket(requestDTO);
        return new ResponseEntity<>(respuesta, HttpStatus.CREATED);
    }

    // Descargar el PDF generado para guardarlo en el computador
    @GetMapping("/{id}/descargar")
    public ResponseEntity<byte[]> descargarArchivoGenerado(@PathVariable Long id) throws IOException {
        return pdf(ticketService.descargarArchivoGenerado(id), "resumen_" + id + ".pdf");
    }

    // Subir el resumen generado a S3 (carpeta = número del resumen)
    @PostMapping("/{id}/subir-s3")
    public ResponseEntity<TicketResponseDTO> subirAS3(@PathVariable Long id) throws IOException {
        return ResponseEntity.ok(ticketService.subirTicketAS3(id));
    }

    // Modificar el resumen (regenera el PDF y lo reemplaza en S3)
    @PutMapping("/{id}")
    public ResponseEntity<TicketResponseDTO> modificarTicket(@PathVariable Long id, @Valid @RequestBody TicketRequestDTO requestDTO) throws IOException {
        return ResponseEntity.ok(ticketService.modificarTicket(id, requestDTO));
    }

    // Descargar el archivo desde S3
    @GetMapping("/{id}/descargar-s3")
    public ResponseEntity<byte[]> descargarTicketS3(@PathVariable Long id) {
        return pdf(ticketService.descargarTicketPorS3(id), "resumen_" + id + ".pdf");
    }

    // Borrar solo el archivo del resumen en S3
    @DeleteMapping("/{id}/archivo-s3")
    public ResponseEntity<TicketResponseDTO> eliminarArchivoS3(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.eliminarArchivoS3(id));
    }

    // Eliminar el resumen completo (BD + archivos)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminarTicket(@PathVariable Long id) {
        ticketService.eliminarTicket(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/historial")
    public ResponseEntity<List<TicketResponseDTO>> consultarHistorial(
            @RequestParam(required = false) String eventoId,
            @RequestParam(required = false) String usuarioId) {
        return ResponseEntity.ok(ticketService.consultarHistorial(eventoId, usuarioId));
    }

    @GetMapping("/estadisticas/{eventoId}")
    public ResponseEntity<EstadisticasResponseDTO> obtenerEstadisticas(@PathVariable String eventoId) {
        return ResponseEntity.ok(ticketService.obtenerEstadisticas(eventoId));
    }
}
