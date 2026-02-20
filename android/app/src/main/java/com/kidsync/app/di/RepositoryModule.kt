package com.kidsync.app.di

import com.kidsync.app.data.repository.ExpenseRepositoryImpl
import com.kidsync.app.domain.repository.ExpenseRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindExpenseRepository(impl: ExpenseRepositoryImpl): ExpenseRepository
}
