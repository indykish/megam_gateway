/* 
** Copyright [2013-2014] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package models.json

import scalaz._
import scalaz.NonEmptyList._
import Scalaz._
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import controllers.funnel.SerializationBase
import models._

/**
 * @author rajthilak
 *
 */
object MarketPlaceAddonsConfigurationResultsSerialization extends SerializationBase[MarketPlaceAddonsConfigurationResults] {
  protected val JSONClazKey = controllers.Constants.JSON_CLAZ
  protected val ResultsKey = "results"

  implicit override val writer = new JSONW[MarketPlaceAddonsConfigurationResults] {
    override def write(h: MarketPlaceAddonsConfigurationResults): JValue = {
      val nrsList: NonEmptyList[JValue] = h.map {
        nrOpt: Option[MarketPlaceAddonsConfigurationResult] =>
          (nrOpt.map { nr: MarketPlaceAddonsConfigurationResult => nr.toJValue }).getOrElse(JNothing)
      }
      JObject(JField(JSONClazKey, JString("Megam::MarketPlaceAddonsConfigurationCollection")) :: JField(ResultsKey, JArray(nrsList.list)) :: Nil)
    }
  }

  
  implicit override val reader = new JSONR[MarketPlaceAddonsConfigurationResults] {
    override def read(json: JValue): Result[MarketPlaceAddonsConfigurationResults] = {
      json match {
        case JArray(jObjectList) => {
          val list = jObjectList.flatMap { jValue: JValue =>
            MarketPlaceAddonsConfigurationResult.fromJValue(jValue) match {
              case Success(nr)   => List(nr)
              case Failure(fail) => List[MarketPlaceAddonsConfigurationResult]()
            }
          } map { x: MarketPlaceAddonsConfigurationResult => x.some }
          //this is screwy. Making the RequestResults as Option[NonEmptylist[RequestResult]] will solve it.
          val nrs: MarketPlaceAddonsConfigurationResults = list.toNel.getOrElse(nels(none))
          nrs.successNel[Error]
        }
        case j => UnexpectedJSONError(j, classOf[JArray]).failureNel[MarketPlaceAddonsConfigurationResults]
      }
    }
  }
}