package io.nexus.smartfactory.store;

import io.nexus.contracts.Domain.TransportKind;

/**
 * One container the operation needs: SUPPLY of a material, or WIP from the previous step.
 * id = the request id of the transport that first brought it; when the container is moved to another machine
 * the same row gets the new transport id and delivered = false.
 *
 * @param transportId null while a WIP container waits for a conveyor hand-over
 */
public record OperationMaterial(String id, String orderId, int stepSeq, TransportKind kind, String materialId,
                                int quantity, String transportId, boolean delivered) {}
