package xitrum.routing

import java.io.{ByteArrayInputStream, DataInputStream}
import java.lang.reflect.Method
import java.util.{List => JList}

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import javassist.{ClassClassPath, ClassPool}
import javassist.bytecode.{AnnotationsAttribute, ClassFile, MethodInfo, AccessFlag}
import javassist.bytecode.annotation.{Annotation, MemberValue, ArrayMemberValue, StringMemberValue, IntegerMemberValue}
import sclasner.{FileEntry, Scanner}

import xitrum.{Action, Logger, SockJsActor}
import xitrum.annotation._
import xitrum.sockjs.SockJsPrefix

/** Scan all classes to collect routes from actions. */
class RouteCollector extends Logger {
  /** @return (normal routes, SockJS routes without prefix, SockJS route map) */
  def deserializeCacheFileOrRecollect(cachedFileName: String):
    (SerializableRouteCollection, SerializableRouteCollection, Map[String, SockJsClassAndOptions]) =
  {
    val normal              = new SerializableRouteCollection
    val sockJsWithoutPrefix = new SerializableRouteCollection
    val sockJsMap           = Map[String, SockJsClassAndOptions]()
    Scanner.foldLeft(cachedFileName, (normal, sockJsWithoutPrefix, sockJsMap), discovered _)
  }

  //----------------------------------------------------------------------------

  private def discovered(
      normal_sockJsWithoutPrefix_sockJsMap: (SerializableRouteCollection, SerializableRouteCollection, Map[String, SockJsClassAndOptions]),
      entry:                                FileEntry
  ): (SerializableRouteCollection, SerializableRouteCollection, Map[String, SockJsClassAndOptions]) =
  {
    try {
      if (entry.relPath.endsWith(".class")) {
        val bais = new ByteArrayInputStream(entry.bytes)
        val dis  = new DataInputStream(bais)
        val cf   = new ClassFile(dis)
        dis.close()
        bais.close()
        val newSockJsMap = processDiscovered(normal_sockJsWithoutPrefix_sockJsMap, cf)

        val (normal, sockJsWithoutPrefix, _) = normal_sockJsWithoutPrefix_sockJsMap
        (normal, sockJsWithoutPrefix, newSockJsMap)
      } else {
        normal_sockJsWithoutPrefix_sockJsMap
      }
    } catch {
      case NonFatal(e) =>
        logger.warn("Could not scan route for " + entry.relPath + " in " + entry.container, e)
        normal_sockJsWithoutPrefix_sockJsMap
    }
  }

  private def processDiscovered(
      normal_sockJsWithoutPrefix_sockJsMap: (SerializableRouteCollection, SerializableRouteCollection, Map[String, SockJsClassAndOptions]),
      classFile:                            ClassFile
  ): Map[String, SockJsClassAndOptions] =
  {
    val (normal, sockJsWithoutPrefix, sockJsMap) = normal_sockJsWithoutPrefix_sockJsMap
    val aa = classFile.getAttribute(AnnotationsAttribute.visibleTag).asInstanceOf[AnnotationsAttribute]
    if (aa == null) return sockJsMap

    val annotations = aa.getAnnotations
    val className   = classFile.getName
    val fromSockJs  = className.startsWith(classOf[SockJsPrefix].getPackage.getName)
    val routes      = if (fromSockJs) sockJsWithoutPrefix else normal
    collectNormalRoutes(routes, className, annotations)
    collectErrorRoutes (routes, className, annotations)

    collectSockJsMap(sockJsMap, className, annotations)
  }

  private def collectNormalRoutes(
      routes:      SerializableRouteCollection,
      className:   String,
      annotations: Array[Annotation])
  {
    var routeOrder           = 0  // -1: first, 1: last, 0: other
    var cacheSecs            = 0  // < 0: cache action, > 0: cache page, 0: no cache
    var method_pattern_coll = ArrayBuffer[(String, String)]()

    annotations.foreach { a =>
      val tn = a.getTypeName
      optRouteOrder(tn)         .foreach { order => routeOrder = order }
      optCacheSecs(a, tn)       .foreach { secs  => cacheSecs  = secs  }
      optMethodAndPattern(a, tn).foreach { m_ps  => method_pattern_coll.append(m_ps) }
    }

    method_pattern_coll.foreach { case (method, pattern) =>
      val compiledPattern   = RouteCompiler.compile(pattern)
      val serializableRoute = new SerializableRoute(method, compiledPattern, className, cacheSecs)
      val coll              = (routeOrder, method) match {
        case (-1, "GET") => routes.firstGETs
        case ( 1, "GET") => routes.lastGETs
        case ( 0, "GET") => routes.otherGETs

        case (-1, "POST") => routes.firstPOSTs
        case ( 1, "POST") => routes.lastPOSTs
        case ( 0, "POST") => routes.otherPOSTs

        case (-1, "PUT") => routes.firstPUTs
        case ( 1, "PUT") => routes.lastPUTs
        case ( 0, "PUT") => routes.otherPUTs

        case (-1, "DELETE") => routes.firstDELETEs
        case ( 1, "DELETE") => routes.lastDELETEs
        case ( 0, "DELETE") => routes.otherDELETEs

        case (-1, "OPTIONS") => routes.firstOPTIONSs
        case ( 1, "OPTIONS") => routes.lastOPTIONSs
        case ( 0, "OPTIONS") => routes.otherOPTIONSs

        case (-1, "WEBSOCKET") => routes.firstWEBSOCKETs
        case ( 1, "WEBSOCKET") => routes.lastWEBSOCKETs
        case ( 0, "WEBSOCKET") => routes.otherWEBSOCKETs
      }
      coll.append(serializableRoute)
    }
  }

  private def collectErrorRoutes(
      routes:      SerializableRouteCollection,
      className:   String,
      annotations: Array[Annotation])
  {
    annotations.foreach { a =>
      val tn = a.getTypeName
      if (tn == classOf[Error404].getName) routes.error404 = Some(className)
      if (tn == classOf[Error500].getName) routes.error500 = Some(className)
    }
  }

  private def collectSockJsMap(
      sockJsMap:   Map[String, SockJsClassAndOptions],
      className:   String,
      annotations: Array[Annotation]
  ): Map[String, SockJsClassAndOptions] =
  {
    var pathPrefix: String = null
    var noWebSocket  = false
    var cookieNeeded = false

    annotations.foreach { a =>
      val tn = a.getTypeName
      if (tn == classOf[SOCKJS].getName)             pathPrefix   = getString(a)
      if (tn == classOf[SockJsNoWebSocket].getName)  noWebSocket  = true
      if (tn == classOf[SockJsCookieNeeded].getName) cookieNeeded = true
    }

    if (pathPrefix == null) {
      sockJsMap
    } else {
      val klass = Class.forName(className).asInstanceOf[Class[SockJsActor]]
      sockJsMap + (pathPrefix -> new SockJsClassAndOptions(klass, !noWebSocket, cookieNeeded))
    }
  }

  //----------------------------------------------------------------------------

  private def optRouteOrder(annotationTypeName: String): Option[Int] = {
    if (annotationTypeName == classOf[First].getName) return Some(-1)
    if (annotationTypeName == classOf[Last].getName)  return Some(1)
    None
  }

  private def optCacheSecs(annotation: Annotation, annotationTypeName: String): Option[Int] = {
    if (annotationTypeName == classOf[CacheActionDay].getName)
      return Some(-getInt(annotation) * 24 * 60 * 60)

    if (annotationTypeName == classOf[CacheActionHour].getName)
      return Some(-getInt(annotation)      * 60 * 60)

    if (annotationTypeName == classOf[CacheActionMinute].getName)
      return Some(-getInt(annotation)           * 60)

    if (annotationTypeName == classOf[CacheActionSecond].getName)
      return Some(-getInt(annotation))

    if (annotationTypeName == classOf[CachePageDay].getName)
      return Some(getInt(annotation) * 24 * 60 * 60)

    if (annotationTypeName == classOf[CachePageHour].getName)
      return Some(getInt(annotation)      * 60 * 60)

    if (annotationTypeName == classOf[CachePageMinute].getName)
      return Some(getInt(annotation)           * 60)

    if (annotationTypeName == classOf[CachePageSecond].getName)
      return Some(getInt(annotation))

    None
  }

  /** @return Option[(method, pattern)] */
  private def optMethodAndPattern(annotation: Annotation, annotationTypeName: String): Option[(String, String)] = {
    if (annotationTypeName == classOf[GET].getName)
      return Some("GET", getString(annotation))

    if (annotationTypeName == classOf[POST].getName)
      return Some("POST", getString(annotation))

    if (annotationTypeName == classOf[PUT].getName)
      return Some("PUT", getString(annotation))

    if (annotationTypeName == classOf[DELETE].getName)
      return Some("DELETE", getString(annotation))

    if (annotationTypeName == classOf[OPTIONS].getName)
      return Some("OPTIONS", getString(annotation))

    if (annotationTypeName == classOf[WEBSOCKET].getName)
      return Some("WEBSOCKET", getString(annotation))

    None
  }

  private def getInt(annotation: Annotation): Int =
    annotation.getMemberValue("value").asInstanceOf[IntegerMemberValue].getValue

  private def getString(annotation: Annotation): String =
    annotation.getMemberValue("value").asInstanceOf[StringMemberValue].getValue
}
