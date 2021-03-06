/*
 * Copyright (c) 2018 Vanniktech - Niklas Baudy
 * Modifications Copyright (c) 2020. Jason Burgess
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ml.introspectsoft.rxbilling

/**
 * Billing not supported exception.
 *
 * @param[responseCode] billing API response code provided
 */
class NoBillingSupportedException(@BillingResponse responseCode: Int) :
    RuntimeException("Billing is not supported. ResponseCode: $responseCode") {

    companion object {
        const val serialVersionUID = 528555849848598969L
    }

}