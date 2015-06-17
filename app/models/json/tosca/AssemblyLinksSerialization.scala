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
import scala.collection.mutable.ListBuffer
import controllers.funnel.SerializationBase
import models.tosca._
import models.json.tosca._
import java.nio.charset.Charset
/**
 * @author rajthilak
 *
 */
object AssemblyLinksSerialization extends SerializationBase[AssemblyLinks] {
  implicit val formats = DefaultFormats
  protected val JSONClazKey = controllers.Constants.JSON_CLAZ
  protected val ResultsKey = "assemblies"

  implicit override val writer = new JSONW[AssemblyLinks] {
    override def write(h: AssemblyLinks): JValue = {
      val nrsList: Option[List[JValue]] = h.map {
        nrOpt: String => toJSON(nrOpt)
      }.some
      
      JArray(nrsList.getOrElse(List.empty[JValue]))
    }
  }

  implicit override val reader = new JSONR[AssemblyLinks] {
    override def read(json: JValue): Result[AssemblyLinks] = {
      var list = new ListBuffer[String]()
      json match {
        case JArray(jObjectList) => {
         jObjectList.foreach { jValue: JValue =>
            list += jValue.extract[String]
            play.api.Logger.debug(("%-20s -->[%s]").format("value--------------------------", list))
          }.some

          val nrs: AssemblyLinks = list.toList
          nrs.successNel[Error]
        }
        case j => UnexpectedJSONError(j, classOf[JArray]).failNel[AssemblyLinks]
      }
    }
  }
}