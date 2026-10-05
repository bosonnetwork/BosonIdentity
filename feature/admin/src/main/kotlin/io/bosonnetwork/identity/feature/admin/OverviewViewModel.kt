/*
 * Copyright (c) 2023 -      bosonnetwork.io
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

package io.bosonnetwork.identity.feature.admin

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.director.client.DirectorAdmin
import io.bosonnetwork.identity.core.boson.AdminSession
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await

/** The node at a glance. */
data class NodeOverview(
    val nodeId: String,
    val name: String?,
    val software: String?,
    val version: String?,
    /** When the node started, in epoch milliseconds; 0 if it didn't say. */
    val startedAt: Long,
    val running: Boolean,
    /** The services it runs, by name (or id when unnamed). */
    val services: List<String>,
    val users: Long,
    val plans: Int,
    val activePlans: Int,
    /** Null when the node doesn't federate. */
    val federatedNodes: Long?,
    val blacklisted: Long,
)

/** Overview: the node's status and how many users, plans, federated nodes and blacklist entries it has. */
@HiltViewModel
class OverviewViewModel @Inject constructor(
    session: AdminSession,
    @ApplicationContext context: Context,
) : AdminSectionViewModel<NodeOverview>(session, context) {

    init {
        watchSession()
    }

    override suspend fun load(admin: DirectorAdmin): NodeOverview = coroutineScope {
        // Counting asks for one item per list: the page carries the total.
        val status = async { admin.nodeStatus.await() }
        val users = async { admin.listUsers(1, 1).await().totalItems() }
        val plans = async { admin.listPlans().await() }
        val blacklisted = async { admin.listBlacklistedNodes(1, 1).await().totalItems() }
        val federated = async {
            try {
                admin.listFederatedNodes(1, 1).await().totalItems()
            } catch (e: Exception) {
                if (e.isNotEnabled()) null else throw e
            }
        }
        val s = status.await()
        val allPlans = plans.await()
        NodeOverview(
            nodeId = s.nodeId.toString(),
            name = s.name.orElse(null)?.takeIf { it.isNotBlank() },
            software = s.software.orElse(null)?.takeIf { it.isNotBlank() },
            version = s.version.orElse(null)?.takeIf { it.isNotBlank() },
            startedAt = s.startedAt,
            running = s.isRunning,
            services = s.services.map { it.serviceName.orElse(null)?.takeIf { name -> name.isNotBlank() } ?: it.serviceId },
            users = users.await(),
            plans = allPlans.size,
            activePlans = allPlans.count { it.isActive },
            federatedNodes = federated.await(),
            blacklisted = blacklisted.await(),
        )
    }
}
