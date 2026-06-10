package com.moviebox.tv.net

import com.moviebox.tv.data.dto.ApiResponse
import com.moviebox.tv.data.dto.CaptionsData
import com.moviebox.tv.data.dto.HomeData
import com.moviebox.tv.data.dto.ItemDetailsData
import com.moviebox.tv.data.dto.ResourceData
import com.moviebox.tv.data.dto.SearchData
import com.moviebox.tv.data.dto.SearchRequest
import com.moviebox.tv.data.dto.SeasonsData
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface MovieBoxApi {

    @GET(Constants.MAIN_PAGE_PATH)
    suspend fun home(
        @Query("page") page: Int = 1,
        @Query("tabId") tabId: Int = 0,
        @Query("version") version: String = "",
    ): ApiResponse<HomeData>

    @POST(Constants.SEARCH_PATH)
    suspend fun search(@Body request: SearchRequest): ApiResponse<SearchData>

    @GET(Constants.SUBJECT_GET_PATH)
    suspend fun itemDetails(
        @Query("subjectId") subjectId: String,
    ): ApiResponse<ItemDetailsData>

    @GET(Constants.SEASON_INFO_PATH)
    suspend fun seasonInfo(
        @Query("subjectId") subjectId: String,
    ): ApiResponse<SeasonsData>

    @GET(Constants.RESOURCE_PATH)
    suspend fun resource(
        @Query("subjectId") subjectId: String,
        @Query("resolution") resolution: Int,
        @Query("page") page: Int = 1,
        @Query("perPage") perPage: Int = 20,
    ): ApiResponse<ResourceData>

    @GET(Constants.EXT_CAPTIONS_PATH)
    suspend fun extCaptions(
        @Query("subjectId") subjectId: String,
        @Query("resourceId") resourceId: String,
    ): ApiResponse<CaptionsData>
}
