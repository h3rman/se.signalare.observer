/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.signalare.observer

import android.content.ContentResolver
import se.signalare.observer.iab.BillingModule
import se.signalare.observer.data.DataModule
import se.signalare.observer.database.DatabaseModule
import se.signalare.observer.download.AppDownloadManager
import se.signalare.observer.scheduler.ScheduleManager
import se.signalare.observer.scheduler.WorkScheduler
import se.signalare.observer.service.AppUpdater
import se.signalare.observer.service.InAppMessageProvider
import se.signalare.observer.service.NoOpInAppMessageProvider
import se.signalare.observer.service.ServiceModule
import se.signalare.observer.rpnproxy.StateMachineDatabaseSyncService
import se.signalare.observer.rpnproxy.SubscriptionStateMachineV2
import se.signalare.observer.util.Constants
import se.signalare.observer.util.OrbotHelper
import se.signalare.observer.viewmodel.ViewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private val rootModule = module { single<ContentResolver> { androidContext().contentResolver } }
private val updaterModule = module {
    single { NonStoreAppUpdater(Constants.RETHINK_APP_UPDATE_CHECK, get()) }
    single<AppUpdater> { get<NonStoreAppUpdater>() }
    // Default no-op provider; the play flavor overrides this with PlayInAppMessageProvider.
    single<InAppMessageProvider> { NoOpInAppMessageProvider() }
}

private val updaterModules = listOf(updaterModule)

private val orbotHelperModule = module { single { OrbotHelper(androidContext(), get(), get()) } }

private val appDownloadManagerModule = module {
    single { AppDownloadManager(androidContext(), get()) }
}

private val workerModule = module { single { WorkScheduler(androidContext()) } }

private val schedulerModule = module { single { ScheduleManager(androidContext()) } }

private val stateMachine = module {
    single { SubscriptionStateMachineV2() }
    single { StateMachineDatabaseSyncService() }
}

private val stateMachineModules = listOf(stateMachine)

val AppModules: List<Module> by lazy {
    mutableListOf<Module>().apply {
        add(rootModule)
        addAll(DatabaseModule.modules)
        addAll(ViewModelModule.modules)
        addAll(DataModule.modules)
        addAll(ServiceModule.modules)
        addAll(stateMachineModules)
        addAll(updaterModules)
        add(schedulerModule)
        add(workerModule)
        add(orbotHelperModule)
        add(appDownloadManagerModule)
        add(BillingModule.billingModules)
    }
}
