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
import scalaz.Validation
import scalaz.Validation._
import Scalaz._
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.util.Date
import java.nio.charset.Charset
import controllers.funnel.FunnelErrors._
import controllers.Constants._
import controllers.funnel.SerializationBase
import models.{RequestResult}

/**
 * @author ram
 *
 */
class RequestResultSerialization(charset: Charset = UTF8Charset) extends SerializationBase[RequestResult] {
  protected val JSONClazKey = controllers.Constants.JSON_CLAZ
  protected val IdKey = "id"
  protected val NodeIDKey = "node_id"
  protected val NodeNameKey = "node_name"
  protected val ReqTypeKey = "req_type"
  protected val CreatedAtKey ="created_at" 

  override implicit val writer = new JSONW[RequestResult] {

    override def write(h: RequestResult): JValue = {
      JObject(
        JField(IdKey, toJSON(h.id)) ::
          JField(NodeIDKey, toJSON(h.node_id)) ::
          JField(JSONClazKey, toJSON("Megam::Request")) ::
          JField(NodeNameKey, toJSON(h.node_name)) ::
          JField(ReqTypeKey, toJSON(h.req_type)) ::
          JField(CreatedAtKey, toJSON(h.created_at)) :: Nil)
    }
  }

  override implicit val reader = new JSONR[RequestResult] {

    override def read(json: JValue): Result[RequestResult] = {
      val idField = field[String](IdKey)(json)
      val nodeIdField = field[String](NodeIDKey)(json)
      val nodeNameField = field[String](NodeNameKey)(json)
      val reqtypeField = field[String](ReqTypeKey)(json)
      val createdAtField = field[String](CreatedAtKey)(json)

      (idField |@| nodeIdField |@| nodeNameField |@| reqtypeField |@| createdAtField) {
        (id: String, node_id: String, node_name: String, req_type: String, created_at: String) =>
          new RequestResult(id, node_id, node_name, req_type, created_at)
      }
    }
  }
}