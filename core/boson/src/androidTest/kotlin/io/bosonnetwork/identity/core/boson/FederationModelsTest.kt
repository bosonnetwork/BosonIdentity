/*
 * Copyright (c) 2026 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.identity.core.boson

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.FederatedHost
import io.bosonnetwork.director.client.FederatedNode
import io.bosonnetwork.director.client.FederationProposal
import io.bosonnetwork.json.Json
import io.bosonnetwork.service.Admission
import io.bosonnetwork.service.FederatedNodeDescriptor
import io.bosonnetwork.service.FederationContext
import io.bosonnetwork.service.FederationMode
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android/Jackson decoding: mocks and desktop tests cannot establish release compatibility. */
@RunWith(AndroidJUnit4::class)
class FederationModelsTest {
    private val nodeId = Id.random()

    @Test
    fun relationshipDecodesAdmissionReachAndNestedHealth() {
        val node = Json.parse("""{
            "nodeId":"$nodeId", "state":"active", "origin":"protocol", "note":"partner",
            "admission":"ACTIVE", "reach":"PENDING", "createdAt":1000, "updatedAt":2000,
            "health":{"nodeId":"$nodeId", "lastSeenAt":2000, "lastFailureAt":1900,
                "lastFailure":"TIMEOUT", "consecutiveFailures":2, "lastHour":{"TIMEOUT":2,"FUTURE":5}}
        }""", FederatedNode::class.java)
        assertEquals(nodeId, node.id)
        assertEquals(FederatedNode.State.ACTIVE, node.state.orElse(null))
        assertEquals(Admission.Reason.ACTIVE, node.admission.orElse(null))
        assertEquals(Admission.Reason.PENDING, node.reach.orElse(null))
        assertEquals(2_000L, node.updatedAt)
        val health = node.health.orElseThrow()
        assertTrue(health.isFailing)
        assertEquals(FederationContext.Outcome.TIMEOUT, health.lastFailure.orElse(null))
        assertEquals(mapOf(FederationContext.Outcome.TIMEOUT to 2), health.lastHour)
    }

    @Test
    fun descriptorDecodesNestedServicesAndSignedDates() {
        val peerId = Id.random()
        val descriptor = Json.parse("""{
            "nodeId":"$nodeId", "name":"Fixture", "mode":"mutual", "software":"Boson",
            "version":"3.2.1", "protocols":["boson-federation/1"], "issuedAt":1000,"expiresAt":2000,
            "services":[{"type":"io.bosonnetwork.ionstore","name":"Ion Store","peerId":"$peerId",
                "endpoint":"https://node.example/ion","federation":true,"extra":{"hint":"value"}}]
        }""", FederatedNodeDescriptor::class.java)
        assertEquals(nodeId, descriptor.nodeId)
        assertEquals(FederationMode.MUTUAL, descriptor.mode.orElse(null))
        assertEquals(1_000L, descriptor.issuedAt.toEpochMilli())
        assertEquals(2_000L, descriptor.expiresAt.toEpochMilli())
        val service = descriptor.services.single()
        assertEquals(peerId, service.peerId)
        assertEquals("Ion Store", service.name.orElse(null))
        assertTrue(service.isFederation)
        assertEquals("value", service.extra["hint"])
    }

    @Test
    fun proposalStatusesAndDirectionsUseTheCurrentModel() {
        for (status in listOf("pending", "accepted", "declined", "withdrawn", "expired")) {
            val proposal = Json.parse("""{"id":1,"nodeId":"$nodeId","role":"answer","status":"$status",
                "note":"note","createdAt":1000,"updatedAt":2000,"answeredAt":1500}""", FederationProposal::class.java)
            assertEquals(FederationProposal.Role.ANSWER, proposal.role.orElse(null))
            assertEquals(status, proposal.status.orElseThrow().name.lowercase())
            assertEquals("note", proposal.note.orElse(null))
            assertEquals(1_500L, proposal.answeredAt)
        }
    }

    @Test
    fun absentAndUnknownOptionalValuesRemainUnrecognized() {
        val node = Json.parse("""{"nodeId":"$nodeId","state":"future","admission":"FUTURE"}""", FederatedNode::class.java)
        assertFalse(node.state.isPresent)
        assertFalse(node.admission.isPresent)
        assertFalse(node.isAdmitted)
        assertFalse(node.health.isPresent)
        val proposal = Json.parse("""{"id":1,"nodeId":"$nodeId","role":"future","status":"future"}""", FederationProposal::class.java)
        assertFalse(proposal.role.isPresent)
        assertFalse(proposal.status.isPresent)
        val descriptor = Json.parse("""{"nodeId":"$nodeId","mode":"future",
            "services":[{"type":"fixture","peerId":"$nodeId","endpoint":"https://node.example"}]}""", FederatedNodeDescriptor::class.java)
        assertFalse(descriptor.mode.isPresent)
        assertFalse(descriptor.services.single().isFederation)
    }

    @Test
    fun unresolvedHostRemainsNullableWithDistinctCounts() {
        val host = Json.parse("""{"host":null,"verifiedNodes":0,"failedNodes":2,
            "notFederatingNodes":1,"lastCheckedAt":2000}""", FederatedHost::class.java)
        assertFalse(host.host.isPresent)
        assertEquals(0, host.verifiedNodes)
        assertEquals(2, host.failedNodes)
        assertEquals(1, host.notFederatingNodes)
        assertEquals(2_000L, host.lastCheckedAt)
    }

    @Test
    fun currentVertxStartsOnAndroid() {
        val vertx = NodeConnections.newVertx()
        vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
    }
}
