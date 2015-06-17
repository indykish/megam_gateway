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
package models.json.tosca

import scalaz._
import scalaz.NonEmptyList._
import Scalaz._
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import controllers.funnel.SerializationBase
import models.tosca._
import models.json.tosca._
import java.nio.charset.Charset
/**
 * @author rajthilak
 *
 */
object CloudSettingsListSerialization extends SerializationBase[CloudSettingsList] {

  protected val JSONClazKey = controllers.Constants.JSON_CLAZ
  protected val ResultsKey = "cloudsettinglist"

  implicit override val writer = new JSONW[CloudSettingsList] {
    override def write(h: CloudSettingsList): JValue = {
      val nrsList: Option[List[JValue]] = h.map {
        nrOpt: CloudSetting => println(nrOpt); nrOpt.toJValue
      }.some
      
      JArray(nrsList.getOrElse(List.empty[JValue]))
    }
  }

  implicit override val reader = new JSONR[CloudSettingsList] {
    override def read(json: JValue): Result[CloudSettingsList] = {
      json match {
        case JArray(jObjectList) => {
          val list = jObjectList.flatMap { jValue: JValue =>
            CloudSetting.fromJValue(jValue) match {
              case Success(nr) => List(nr)
              case Failure(fail) => List[CloudSetting]()
            }
          }.some

          val nrs: CloudSettingsList = CloudSettingsList(list.getOrElse(CloudSettingsList.empty))
          nrs.successNel[Error]
        }
        case j => UnexpectedJSONError(j, classOf[JArray]).failNel[CloudSettingsList]
      }
    }
  }
}