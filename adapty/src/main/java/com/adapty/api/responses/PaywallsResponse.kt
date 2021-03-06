package com.adapty.api.responses

import com.adapty.api.entity.paywalls.DataContainer
import com.adapty.api.entity.paywalls.MetaGetContainerRes
import com.google.gson.annotations.SerializedName

class PaywallsResponse {
    @SerializedName("data")
    var data: ArrayList<DataContainer>? = null

    @SerializedName("meta")
    var meta: MetaGetContainerRes? = null
}