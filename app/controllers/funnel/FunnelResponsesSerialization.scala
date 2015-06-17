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
package controllers.funnel

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
//import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import controllers.funnel._

/**
 * @author ram
 *
 */
object FunnelResponsesSerialization extends SerializationBase[FunnelResponses] {

  implicit override val writer = new JSONW[FunnelResponses] {
    override def write(h: FunnelResponses): JValue = {
      val frsList: List[JValue] = h.map {
        frList: FunnelResponseList =>
          play.api.Logger.debug(("%-20s -->[%s]").format("-------------((())))------->", frList))
          (frList.list.map { fr: FunnelResponse => fr.toJValue }).toList
      } | List[JObject]() 


      JArray(frsList)
    }
  }

  /* Read - JArray(List[FunnelResponse]) which translates to :
        JArray(List(
          JObject(
            List(
              JField(name,JInt(code)),
              JField(value,JString(msg))
              JField(value,JString(more))
              JField(value,JString(severity))
            )
          )
        )
      )
      FunnelResponse already has an implicit reader, hence use it.
       */
  implicit override val reader = new JSONR[FunnelResponses] {
    override def read(json: JValue): Result[FunnelResponses] = {
      json match {
        case JArray(jObjectList) => {
          val list = jObjectList.flatMap { jValue: JValue =>
            FunnelResponse.fromJValue(jValue) match {
              case Success(fr)   => List(fr)
              case Failure(fail) => List[FunnelResponse]()
            }
          } map { x: FunnelResponse => x }
          val frs: FunnelResponses = FunnelResponses(list)
          frs.successNel[Error]
        }
        case j => UnexpectedJSONError(j, classOf[JArray]).failureNel[FunnelResponses]
      }
    }
  }
}