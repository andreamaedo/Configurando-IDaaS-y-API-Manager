package com.duoc.cloud.service;

import com.duoc.cloud.dto.EstadisticasResponseDTO;
import com.duoc.cloud.dto.TicketRequestDTO;
import com.duoc.cloud.dto.TicketResponseDTO;
import com.duoc.cloud.exception.RecursoNoEncontradoException;
import com.duoc.cloud.model.Ticket;
import com.duoc.cloud.repository.TicketRepository;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final EfsStorageService efsStorageService;
    private final S3StorageService s3StorageService;

    public TicketService(TicketRepository ticketRepository, EfsStorageService efsStorageService, S3StorageService s3StorageService) {
        this.ticketRepository = ticketRepository;
        this.efsStorageService = efsStorageService;
        this.s3StorageService = s3StorageService;
    }

    /**
     * Clave en S3: cada resumen queda en una carpeta cuyo nombre es el número del resumen.
     * Ejemplo: RES-1001/resumen_RES-1001.pdf
     */
    private String construirS3Key(String numeroResumen) {
        return numeroResumen + "/resumen_" + numeroResumen + ".pdf";
    }

    private Ticket buscar(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Resumen de compra no encontrado con id: " + id));
    }

    // 1. Crear resumen de compra y generar el archivo PDF físico
    public TicketResponseDTO generarTicket(TicketRequestDTO dto) throws IOException {
        Ticket ticket = Ticket.builder()
                .codigoTicket(dto.getCodigoTicket())
                .eventoId(dto.getEventoId())
                .usuarioId(dto.getUsuarioId())
                .nombreAsistente(dto.getNombreAsistente())
                .precio(dto.getPrecio())
                .estado(dto.getEstado() != null ? dto.getEstado() : "RESERVADO")
                .s3Key(construirS3Key(dto.getCodigoTicket()))
                .build();

        String efsPath = efsStorageService.generarYGuardarTicketPdf(ticket);
        ticket.setEfsPath(efsPath);

        Ticket guardado = ticketRepository.save(ticket);
        return mapToDTO(guardado);
    }

    // 2. Descargar el PDF generado para guardarlo en el computador
    public byte[] descargarArchivoGenerado(Long id) throws IOException {
        Ticket ticket = buscar(id);
        File archivo = new File(ticket.getEfsPath());
        if (!archivo.exists()) {
            // Si el archivo se perdió (p. ej. reinicio del contenedor) se vuelve a generar
            ticket.setEfsPath(efsStorageService.generarYGuardarTicketPdf(ticket));
            ticketRepository.save(ticket);
            archivo = new File(ticket.getEfsPath());
        }
        return Files.readAllBytes(archivo.toPath());
    }

    // 3. Subir el resumen generado al bucket S3 (carpeta = número del resumen)
    public TicketResponseDTO subirTicketAS3(Long id) throws IOException {
        Ticket ticket = buscar(id);

        File archivoLocal = new File(ticket.getEfsPath());
        if (!archivoLocal.exists()) {
            ticket.setEfsPath(efsStorageService.generarYGuardarTicketPdf(ticket));
            archivoLocal = new File(ticket.getEfsPath());
        }

        String urlS3 = s3StorageService.subirArchivo(ticket.getS3Key(), archivoLocal);
        ticket.setS3Url(urlS3);

        Ticket actualizado = ticketRepository.save(ticket);
        return mapToDTO(actualizado);
    }

    // 4. Modificar el resumen: regenera el PDF y reemplaza el archivo en S3
    public TicketResponseDTO modificarTicket(Long id, TicketRequestDTO dto) throws IOException {
        Ticket ticket = buscar(id);

        ticket.setNombreAsistente(dto.getNombreAsistente());
        ticket.setPrecio(dto.getPrecio());
        if (dto.getEstado() != null) {
            ticket.setEstado(dto.getEstado());
        }

        String nuevoEfsPath = efsStorageService.generarYGuardarTicketPdf(ticket);
        ticket.setEfsPath(nuevoEfsPath);

        if (ticket.getS3Url() != null) {
            String urlS3 = s3StorageService.actualizarArchivo(ticket.getS3Key(), new File(nuevoEfsPath));
            ticket.setS3Url(urlS3);
        }

        Ticket guardado = ticketRepository.save(ticket);
        return mapToDTO(guardado);
    }

    // 5. Descargar el archivo desde S3
    public byte[] descargarTicketPorS3(Long id) {
        Ticket ticket = buscar(id);
        if (ticket.getS3Url() == null) {
            throw new IllegalStateException("El resumen " + ticket.getCodigoTicket() + " aún no ha sido subido a S3");
        }
        return s3StorageService.descargarArchivo(ticket.getS3Key());
    }

    // 6. Borrar solo el archivo en S3 (el resumen sigue existiendo y se puede volver a subir)
    public TicketResponseDTO eliminarArchivoS3(Long id) {
        Ticket ticket = buscar(id);
        if (ticket.getS3Url() == null) {
            throw new IllegalStateException("El resumen " + ticket.getCodigoTicket() + " no tiene archivo en S3");
        }
        s3StorageService.eliminarArchivo(ticket.getS3Key());
        ticket.setS3Url(null);
        return mapToDTO(ticketRepository.save(ticket));
    }

    // 7. Eliminar el resumen completo (BD + archivo local + archivo en S3)
    public void eliminarTicket(Long id) {
        Ticket ticket = buscar(id);

        if (ticket.getS3Url() != null) {
            s3StorageService.eliminarArchivo(ticket.getS3Key());
        }

        if (ticket.getEfsPath() != null) {
            File archivoLocal = new File(ticket.getEfsPath());
            if (archivoLocal.exists()) {
                archivoLocal.delete();
            }
        }

        ticketRepository.delete(ticket);
    }

    public List<TicketResponseDTO> consultarHistorial(String eventoId, String usuarioId) {
        List<Ticket> tickets;
        if (eventoId != null && usuarioId != null) {
            tickets = ticketRepository.findByEventoIdAndUsuarioId(eventoId, usuarioId);
        } else if (eventoId != null) {
            tickets = ticketRepository.findByEventoId(eventoId);
        } else if (usuarioId != null) {
            tickets = ticketRepository.findByUsuarioId(usuarioId);
        } else {
            tickets = ticketRepository.findAll();
        }
        return tickets.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    public EstadisticasResponseDTO obtenerEstadisticas(String eventoId) {
        List<Ticket> tickets = ticketRepository.findByEventoId(eventoId);
        long totalGenerados = tickets.size();
        double totalRecaudado = tickets.stream().mapToDouble(Ticket::getPrecio).sum();

        return EstadisticasResponseDTO.builder()
                .eventoId(eventoId)
                .totalTicketsGenerados(totalGenerados)
                .totalRecaudado(totalRecaudado)
                .build();
    }

    private TicketResponseDTO mapToDTO(Ticket ticket) {
        return TicketResponseDTO.builder()
                .id(ticket.getId())
                .codigoTicket(ticket.getCodigoTicket())
                .eventoId(ticket.getEventoId())
                .usuarioId(ticket.getUsuarioId())
                .nombreAsistente(ticket.getNombreAsistente())
                .precio(ticket.getPrecio())
                .estado(ticket.getEstado())
                .efsPath(ticket.getEfsPath())
                .s3Key(ticket.getS3Key())
                .s3Url(ticket.getS3Url())
                .fechaCreacion(ticket.getFechaCreacion())
                .fechaActualizacion(ticket.getFechaActualizacion())
                .build();
    }
}
