package tugboat

import com.ning.http.client.generators.InputStreamBodyGenerator
import dispatch.{ as, Req }
import dispatch.stream.Strings
import dispatch.stream.StringsByLine
import java.io.{ File, PipedInputStream, PipedOutputStream, InputStream, OutputStream }
import org.json4s.JsonDSL._
import org.json4s.{ JArray, JBool, JInt, JNull, JObject, JString, JValue }
import org.json4s.native.JsonMethods.{ compact, render }
import scala.concurrent.Future
import scala.concurrent.duration._

trait Methods { self: Requests =>

  private object json {
    private[this] val ContentType = "application/json"
    private[this] val Encoding = "UTF-8"
    def content(r: Req) = r.setContentType(ContentType, Encoding)
    def str(jv: JValue) = compact(render(jv))
  }

  case class Auth(_cfg: AuthConfig)
    extends Client.Completion[Unit] { // fixme: better rep
    def user(u: String) = config(
      _cfg.copy(user = u)
    )
    def password(pw: String) = config(
      _cfg.copy(password = pw)
    )
    def email(em: String) = config(
      _cfg.copy(email = em)
    )
    def server(svr: String) = config(
      _cfg.copy(server = svr)
    )
    def config(cfg: AuthConfig) = copy(_cfg = cfg)
    def apply[T](handler: Client.Handler[T]) =
      request(json.content(host / "auth") <<
              json.str(
                ("username" -> _cfg.user) ~
                ("password" -> _cfg.password) ~
                ("email" -> _cfg.email) ~
                ("serveraddress" -> _cfg.server)))(handler)
  }

  def version = complete[Version](host / "version")

  def info = complete[Info](host / "info")

  def ping = complete[Unit](host / "_ping")

  def auth(user: String, password: String, email: String): Auth =
    auth(AuthConfig(user, password, email))

  def auth(cfg: AuthConfig): Auth = Auth(cfg)

  object containers {
    private[this] def base = host / "containers"

    /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#list-containers */
    case class Containers(
      private val _all: Option[Boolean]   = None,
      private val _limit: Option[Int]     = None,
      private val _since: Option[String]  = None,
      private val _before: Option[String] = None,
      private val _sizes: Option[Boolean] = None)
      extends Client.Completion[List[tugboat.Container]] {
      def all = copy(_all = Some(true))
      def limit(lim: Int) = copy(_limit = Some(lim))
      def since(s: String) = copy(_since = Some(s))
      def before(b: String) = copy(_before = Some(b))
      def sizes(include: Boolean) = copy(_sizes = Some(include))
      def apply[T](handler: Client.Handler[T]) =
        request(base / "json" <<?
               (Map.empty[String, String]
                ++ _all.map(("all"       -> _.toString))
                ++ _limit.map(("limit"   -> _.toString))
                ++ _before.map(("before" -> _))
                ++ _since.map(("since"   -> _))
                ++ _sizes.map(("size"    -> _.toString))))(handler)
    }    

    /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#create-a-container */
    case class Create(
      private val _config: ContainerConfig,
      private val _name: Option[String]                 = None,
      private val _restartPolicy: Option[RestartPolicy] = None)
      extends Client.Completion[tugboat.Create.Response] {

      def name(n: String) = copy(_name = Some(n))

      def config(cfg: ContainerConfig) = copy(_config = cfg)

      def withConfig(f: ContainerConfig => ContainerConfig) =
        config(f(_config))

      def image(img: String) =
        withConfig(_.copy(image = img))

      def attachStdin(in: Boolean) =
        withConfig(_.copy(attachStdin = in))

      def attachStdout(out: Boolean) =
        withConfig(_.copy(attachStdout = out))

      def attachStderr(err: Boolean) =
        withConfig(_.copy(attachStderr = err))

      def cmd(args: String*) =
        withConfig(_.copy(cmd = args.toSeq))

      def cpuShares(cpu: Int) =
        withConfig(_.copy(cpuShares = cpu))

      def cpuSet(set: String) =
        withConfig(_.copy(cpuSet = set))

      def domainName(name: String) =
        withConfig(_.copy(domainName = name))

      def entryPoint(ep: String*) =
        withConfig(_.copy(entryPoint = ep.toSeq))

      def env(vars: (String, String)*) =
        withConfig(_.copy(env = vars.toMap))

      // todo: types!
      def exposedPorts(ports: String*) =
        withConfig(_.copy(exposedPorts = ports.toSeq))

      def hostname(name: String) =
        withConfig(_.copy(hostname = name))

      def memory(mem: Long) =
        withConfig(_.copy(memory = mem))

      def memorySwap(swap: Long) =
        withConfig(_.copy(memorySwap = swap))

      def networkDisabled(dis: Boolean) =
        withConfig(_.copy(networkDisabled = dis))

      def openStdin(in: Boolean) =
        withConfig(_.copy(openStdin = in))

      def stdinOnce(once: Boolean) =
        withConfig(_.copy(stdinOnce = once))

      def user(u: String) =
        withConfig(_.copy(user = u))

      def tty(is: Boolean) =
        withConfig(_.copy(tty = is))

      def volumes(vx: String*) =
        withConfig(_.copy(volumes = vx.toSeq))

      def workingDir(dir: String) =
        withConfig(_.copy(workingDir = dir))
        
      def restartPolicy(p: RestartPolicy) = copy(
        _restartPolicy = Some(p)
      )

      def apply[T](handler: Client.Handler[T]) =
        request(json.content(base.POST) / "create" <<?
                (Map.empty[String, String]
                 ++ _name.map(("name" -> _)))
                << bodyStr)(handler)

      // config https://github.com/dotcloud/docker/blob/master/runconfig/parse.go#L213
      // host config https://github.com/dotcloud/docker/blob/master/runconfig/parse.go#L236
      // run https://github.com/dotcloud/docker/blob/master/api/client/commands.go#L1897

      def bodyStr = json.str(
        ("Hostname"        -> _config.hostname) ~
        ("Domainname"      -> _config.domainName) ~
        ("ExposedPorts"    -> _config.exposedPorts.map { ep =>
          (ep -> JObject())
        }) ~
        ("User"            -> _config.user) ~
        ("Tty"             -> _config.tty) ~
        ("NetworkDisabled" -> _config.networkDisabled) ~
        ("OpenStdin"       -> _config.openStdin) ~
        ("Memory"          -> _config.memory) ~
        ("CpuShares"       -> _config.cpuShares) ~
        ("Cpuset"          -> _config.cpuSet) ~
        ("AttachStdin"     -> _config.attachStdin) ~
        ("AttachStdout"    -> _config.attachStdout) ~
        ("AttachStderr"    -> _config.attachStderr) ~
        ("Env"             -> _config.env.map { case (k,v) => s"$k=$v" }) ~
        ("Cmd"             -> Option(_config.cmd).filter(_.nonEmpty)) ~
        ("Image"           -> _config.image) ~
        ("Volumes"         -> _config.volumes.map { vol =>
          (vol, JObject())
        }) ~
        ("WorkingDir"      -> _config.workingDir) ~
        ("RestartPolicy"   -> _restartPolicy.map { policy =>
          ("Name" -> policy.name)
        }))
    }

    case class Container(id: String)
      extends Client.Completion[Option[ContainerDetails]] {

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#start-a-container */
      case class Start(_config: HostConfig)
        extends Client.Completion[Unit] { // fixme: better rep
        def config(cfg: HostConfig) = copy(_config = cfg)

        // https://docs.docker.com/userguide/dockerlinks/
        // docker -p  format: ip:hostPort:containerPort | ip::containerPort | hostPort:containerPort

        def bind(containerPort: Port, binding: PortBinding*) = config(
          _config.copy(ports = _config.ports + (containerPort -> binding.toList))
        )

        def links(lx: String*) = config(
          _config.copy(links = lx.toSeq)
        )

        def capAdd(caps: String*) = config(
          _config.copy(capAdd = caps.toSeq)
        )

        def capDrop(caps: String*) = config(
          _config.copy(capDrop = caps.toSeq)
        )

        // todo: complete builder interface
        def apply[T](handler: Client.Handler[T]) =
          request(json.content(base.POST) / id / "start" << bodyStr)(handler)

        def bodyStr = json.str(
          ("Binds" -> Option(_config.binds).filter(_.nonEmpty)) ~
          ("ContainerIDFile" -> _config.containerIdFile) ~
          ("LxcConf" -> _config.lxcConf) ~
          ("Privileged" -> _config.privileged) ~
          ("PortBindings" -> _config.ports.map {
            case (port, bindings) =>
              (port.spec -> bindings.map { binding =>
                ("HostIp" -> binding.hostIp) ~
                ("HostPort" -> binding.hostPort.toString)
              })
          }) ~
          ("Links" -> Option(_config.links).filter(_.nonEmpty)) ~
          ("PublishAllPorts" -> _config.publishAllPorts) ~
          ("Dns" -> Option(_config.dns).filter(_.nonEmpty)) ~
          ("DnsSearch" -> Option(_config.dnsSearch).filter(_.nonEmpty)) ~
          ("NetworkMode" -> _config.networkMode.value) ~
          ("VolumesFrom" -> Option(_config.volumesFrom).filter(_.nonEmpty)) ~
          ("CapAdd" -> Option(_config.capAdd).filter(_.nonEmpty)) ~
          ("CapDrop" -> Option(_config.capDrop).filter(_.nonEmpty)))
      }

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#kill-a-container */
      case class Kill(
        _signal: Option[String] = None)
        extends Client.Completion[Unit] { // fixme: better rep
        def signal(sig: String) = copy(_signal = Some(sig))
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "kill" <<?
                 (Map.empty[String, String]
                  ++ _signal.map(("signal" -> _))))(handler)
      }

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#get-container-logs */
      case class Logs(
        private val _follow: Option[Boolean]     = None,
        private val _stdout: Option[Boolean]     = None,
        private val _stderr: Option[Boolean]     = None,
        private val _timestamps: Option[Boolean] = None)
        extends Client.Stream[String] {
        def stdout(b: Boolean) = copy(_stdout = Some(b))
        def stderr(b: Boolean) = copy(_stderr = Some(b))
        def timestamps(ts: Boolean) = copy(_timestamps = Some(ts))
        def follow(fol: Boolean) = copy(_follow = Some(fol))
        def apply[T](handler: Client.Handler[T]) =
          request(base / id / "logs" <<?
                 (Map.empty[String, String]
                  ++ _follow.map(("follow" -> _.toString))
                  ++ _stdout.map(("stdout" -> _.toString))
                  ++ _stderr.map(("stderr" -> _.toString))
                  ++ _timestamps.map(("timestamps" -> _.toString))))(handler)
      }

      case class Delete(
        private val _volumes: Option[Boolean] = None,
        private val _force: Option[Boolean]   = None)
        extends Client.Completion[Unit] {
        def volumes(v: Boolean) = copy(_volumes = Some(v))
        def force(f: Boolean) = copy(_force = Some(f))
        def apply[T](handler: Client.Handler[T]) =
          request(base.DELETE / id <<?
                 (Map.empty[String, String]
                  ++ _volumes.map(("v" -> _.toString))
                  ++ _force.map(("force" -> _.toString))))(handler)
      }

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#inspect-a-container */
      def apply[T](handler: Client.Handler[T]) =
        request(base / id / "json")(handler)

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#list-processes-running-inside-a-container */
      def top(args: String = "") =
        complete[Top](base / id / "top" <<? Map("ps_args" -> args))
      
      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#get-container-logs */
      def logs = Logs()

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#inspect-changes-on-a-containers-filesystem */
      def changes =
        complete[List[Change]](base / id / "changes")
      
      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#export-a-container */
      def export(toFile: File) =
        request(base / id / "export")(dispatch.as.File(toFile))

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#start-a-container */
      def start = Start(HostConfig())

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#stop-a-container */
      def stop(after: FiniteDuration = 0.seconds) =
        complete[Unit](base.POST / id / "stop" <<? Map("t" -> after.toString))

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#restart-a-container */
      def restart(after: FiniteDuration = 0.seconds) =
        complete[Unit](base.POST / id / "restart" <<? Map("t" -> after.toString))

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#kill-a-container */
      def kill = Kill()

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#pause-a-container */
      def pause =
        complete[Unit](base.POST / id / "pause")

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#unpause-a-container */
      def unpause =
        complete[Unit](base.POST / id / "unpause")

      // todo multiple std in/out
      case class Attach(
        private val _logs: Option[Boolean]   = None,
        private val _stream: Option[Boolean] = None,
        private val _stdin: Option[Boolean]  = None,
        private val _stdout: Option[Boolean] = None,
        private val _stderr: Option[Boolean] = None) {
        def logs(l: Boolean) = copy(_logs = Some(l))
        def stream(s: Boolean) = copy(_stream = Some(s))
        def stdin(s: Boolean) = copy(_stdin = Some(s))
        def stdout(s: Boolean) = copy(_stdout = Some(s))
        def stderr(s: Boolean) = copy(_stderr = Some(s))

        private def req =
          (base.POST / id / "attach"
           <<? Map.empty[String, String]
           ++ _logs.map(("logs"     -> _.toString))
           ++ _stream.map(("stream" -> _.toString))
           ++ _stdin.map(("stdin"   -> _.toString))
           ++ _stdout.map(("stdout" -> _.toString))
           ++ _stderr.map(("stderr" -> _.toString)))

        /** todo: consider processIO https://github.com/scala/scala/blob/v2.11.2/src/library/scala/sys/process/ProcessIO.scala#L1 */
        def apply(in: OutputStream => Unit, out: String => Unit = _ => ()) = {
          val os = new PipedOutputStream()
          val is = new PipedInputStream(os)
          in(os)
          request(req.subject.underlying(_.setBody(new InputStreamBodyGenerator(is))))(
            new StringsByLine[Unit] with Client.StreamErrorHandler[Unit] {
              def onStringBy(str: String) = out(str)
              def onCompleted = ()
            })
        }
      }

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#attach-to-a-container */
      //def attach = Attach()

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#wait-a-container */
      def await =
        complete[Status](base.POST / id / "wait")

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#remove-a-container */
      def delete = Delete()

      // todo: octet stream
      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#copy-files-or-folders-from-a-container */
     /* def cp(resource: String) = new Client.Stream[Unit] {
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "copy")(handler)
      }*/
    }

    /** aka docker ps */
    def list = Containers()

    /** aka docker run */
    def create(image: String) = Create(ContainerConfig(image))

    /** aka docker inspect */
    def get(id: String) = Container(id)
  }

  object images {
    private[this] def base = host / "images"

    case class Images(
      private val _all: Option[Boolean]                       = None,
      private val _filters: Option[Map[String, List[String]]] =  None)
      extends Client.Completion[List[tugboat.Image]] {
      def all = copy(_all = Some(true))
      def filters(fs: Map[String, List[String]]) = copy(_filters = Some(fs))
      // ( aka untagged ) for convenience
      def dangling(dang: Boolean) = filters(Map("dangling" -> (dang.toString :: Nil)))
      def apply[T](handler: Client.Handler[T]) = {
        request(base / "json" <<?
               (Map.empty[String, String]
                ++ _all.map(("all" -> _.toString)))
                ++ _filters.map("filters" -> json.str(_)))(handler)
      }
    }

    /** https://docs.docker.com/reference/api/docker_remote_api_v1.12/#create-an-image */
    case class Pull(
      private val _fromImage: String,
      private val _fromSrc: Option[String]   = None,
      private val _repo: Option[String]      = None,
      private val _tag: Option[String]       = None,
      private val _registry: Option[String]  = None)
      extends Client.Stream[tugboat.Pull.Output] {
      override protected def streamer = { f =>
        /** Like StringsByLine doesn't buffer. The images/create response
         *  returns chunked encoding by with a no explicit terminator for
         *  each chunk (typically a newline separator). We are being optimistic
         *  here in assuming that each logical stream chunk can be encoded
         *  in a single pack of body part bytes. I don't like this but
         *  until docker documents this better, this should work in most cases.
         */
        new Strings[Unit] with Client.StreamErrorHandler[Unit] {
          def onString(str: String) {
            f(implicitly[StreamRep[tugboat.Pull.Output]].map(str.trim))
          }
          def onCompleted = ()
        }
      }

      def fromImage(img: String) = copy(_fromImage = img)
      def fromSrc(src: String) = copy(_fromSrc = Some(src))
      def repo(r: String) = copy(_repo = Some(r))
      // if fromImage includes a tag, foobar:tag, this is not needed
      def tag(t: String) = copy(_tag = Some(t))
      def registry(r: String) = copy(_registry = Some(r))      
      def apply[T](handler: Client.Handler[T]) =
        request(base.POST / "create" <<?
               (Map("fromImage" -> _fromImage)
                ++ _fromSrc.map(("fromSrc" -> _))
                ++ _repo.map(("repo" -> _))
                ++ _tag.map(("tag" -> _))
                ++ _registry.map(("registry" -> _))))(handler)
    }

    case class Image(id: String)
      extends Client.Completion[Option[ImageDetails]] {

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#push-an-image-on-the-registry */
      case class Push(
        _registry: Option[String] = None)
        extends Client.Stream[tugboat.Push.Output] {
        def registry(reg: String) = copy(_registry = Some(reg))
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "push" <:<
                 (Map.empty[String, String]
                  ++ authConfig.map(
                    ("X-Registry-Auth" -> _.headerValue)
                  )) <<?
                 (Map.empty[String, String]
                  ++ _registry.map(("registry" -> _))))(handler)
      }

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#tag-an-image-into-a-repository */
      case class Tag(
        _repo: Option[String]   = None,
        _force: Option[Boolean] = None)
        extends Client.Completion[Unit] {
        def repo(r: String) = copy(_repo = Some(r))
        def force(f: Boolean) = copy(_force = Some(f))
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "tag" <<?
                 (Map.empty[String, String]
                  ++ _repo.map(("repo" -> _))
                  ++ _force.map(("force" -> _.toString))))(handler)
      }
       
      // todo: stream rep
      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#remove-an-image */
      case class Delete(
        private val _force: Option[Boolean]   = None,
        private val _noprune: Option[Boolean] = None)
        extends Client.Stream[String] {
        def force(f: Boolean) = copy(_force = Some(f))
        def noprune(np: Boolean) = copy(_noprune = Some(np))
        def apply[T](handler: Client.Handler[T]) =
          request(base.DELETE / id <<?
                 (Map.empty[String, String]
                  ++ _force.map(("force" -> _.toString))
                  ++ _noprune.map(("noprune" -> _.toString))))(handler)
      }

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#inspect-an-image */
      def apply[T](handler: Client.Handler[T]) =
        request(base / id / "json")(handler)

      /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#get-the-history-of-an-image */
      def history = complete[List[Event]](base / id / "history")

      // todo insert stream
      def insert(url: String, path: String) =
        stream[Unit](base.POST / id  / "insert" <<?
                     Map("url" -> url, "path" -> path))

      def push = Push()

      def tag = Tag()

      def delete = Delete()
    }

    /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#search-images */
    case class Search(
      private val _term: Option[String] = None)
      extends Client.Completion[List[SearchResult]] {
      def term(t: String) = copy(_term = Some(t))
      def apply[T](handler: Client.Handler[T]) =
        request(base / "search" <<? _term.map(("term" -> _)))(handler)
    }

    /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#build-an-image-from-dockerfile-via-stdin */
    case class Build(
      path: File,
      private val _tag: Option[String]      = None,
      private val _q: Option[Boolean]       = None,
      private val _nocache: Option[Boolean] = None,
      private val _rm: Option[Boolean]      = None,
      private val _forcerm: Option[Boolean] = None)
      extends Client.Stream[tugboat.Build.Output] {
      lazy val tarfile = if (path.isDirectory) {
        Tar(path, TmpFile.create, path.getName, zip = true)
      } else path

      def tag(t: String) = copy(_tag = Some(t))
      def verbose(v: Boolean) = copy(_q = Some(!v))
      def nocache(n: Boolean) = copy(_nocache = Some(n))
      def rm(r: Boolean) = copy(_rm = Some(r))
      def forceRm(r: Boolean) = copy(_forcerm = Some(r))
      def apply[T](handler: Client.Handler[T]) =
        request((host.POST / "build" <:< Map(
                "Content-Type" -> "application/tar",
                "Content-Encoding" -> "gzip") ++ authConfig.map(
                  ("X-Registry-Auth" -> _.headerValue)
                 ) <<?
                (Map.empty[String, String]
                 ++ _tag.map(("t" -> _))
                 ++ _q.map(("q" -> _.toString))
                 ++ _nocache.map(("nocache" -> _.toString))
                 ++ _rm.map(("rm" -> _.toString))
                 ++ _forcerm.map(("forcerm" -> _.toString)))
                <<< tarfile))(handler)
    }

    /** https://docs.docker.com/reference/api/docker_remote_api_v1.14/#list-images */
    def list = Images()

    // the api calls this create by the default client calls this pull
    // pull seems more `intention revealing` so let's use that
    def pull(image: String) = Pull(image)
    // but to avoid confustion let's alias it for those reading from the docs
    def create = pull _
    def get(id: String) = Image(id)
    def search = Search()
    /** if path is a directory, it will be bundled into a gzipped tar. otherwise we assume a tar file */
    def build(path: File) = Build(path)
  }
}
