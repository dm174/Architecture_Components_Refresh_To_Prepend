package ru.netology.nmedia.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import ru.netology.nmedia.api.ApiService
import ru.netology.nmedia.dao.PostDao
import ru.netology.nmedia.dao.PostRemoteKeyDao
import ru.netology.nmedia.db.AppDb
import ru.netology.nmedia.entity.PostEntity
import ru.netology.nmedia.entity.PostRemoteKeyEntity
import ru.netology.nmedia.entity.toEntity
import ru.netology.nmedia.error.ApiError

@OptIn(ExperimentalPagingApi::class)
class PostRemoteMediator(
    private val service: ApiService,
    private val db: AppDb,
    private val postDao: PostDao,
    private val postRemoteKeyDao: PostRemoteKeyDao,
) : RemoteMediator<Int, PostEntity>() {
    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PostEntity>
    ): MediatorResult {
        try {
            val response = when (loadType) {
                LoadType.REFRESH -> /* Код с лекции service.getLatest(state.config.initialLoadSize)*/
                    postRemoteKeyDao.max()?.let { id ->
                        service.getAfter(id, state.config.pageSize)
                    } ?: service.getLatest(state.config.pageSize)
                LoadType.PREPEND -> {
                    return MediatorResult.Success(true)
                }
                LoadType.APPEND -> {
                    val id = postRemoteKeyDao.min() ?: return MediatorResult.Success(false)
                    service.getBefore(id, state.config.pageSize)
                }
            }

            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(
                response.code(),
                response.message(),
            )

            if (body.isEmpty()) {
                return MediatorResult.Success(true)
            }

            db.withTransaction {
                when (loadType) {
                    LoadType.REFRESH -> {
                        // Всегда перезаписываем ключ AFTER при REFRESH
                        val firstItemId = body.firstOrNull()?.id
                        if (firstItemId != null) {
                            postRemoteKeyDao.insert(
                                listOf(PostRemoteKeyEntity(
                                    type = PostRemoteKeyEntity.KeyType.AFTER,
                                    id = firstItemId
                                ))
                            )
                        }
                    }

                    LoadType.PREPEND -> {
                        // Ничего не делаем в случае PREPEND
                    }

                    LoadType.APPEND -> {
                        val lastItemId = body.lastOrNull()?.id
                        if (lastItemId != null) {
                            postRemoteKeyDao.insert(
                                listOf(PostRemoteKeyEntity(
                                    type = PostRemoteKeyEntity.KeyType.BEFORE,
                                    id = lastItemId
                                ))
                            )
                        }
                    }
                }
                postDao.insert(body.toEntity())
            }
            return MediatorResult.Success(endOfPaginationReached = body.isEmpty())
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }

}
