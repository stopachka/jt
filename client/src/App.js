import React from "react";
import "./App.css";
import { withRouter} from "react-router";
import { BrowserRouter as Router, Switch, Route, Link } from "react-router-dom";
import * as firebase from "firebase/app";

// Set up Firebase
import "firebase/auth";
import "firebase/database";
firebase.initializeApp({
  apiKey: "AIzaSyCBl-YAARDZuf0KcTIOTcmUZjynaKC7puc",
  authDomain: "journaltogether.firebaseapp.com",
  databaseURL: "https://journaltogether.firebaseio.com",
  projectId: "journaltogether",
  storageBucket: "journaltogether.appspot.com",
  messagingSenderId: "625725315087",
  appId: "1:625725315087:web:a946ce7cf0725381df2b7a",
  measurementId: "G-GYBL76DZQW",
});

function serverPath(path) {
  const root =
    process.env.NODE_ENV === "development" ? "http://localhost:8080" : "";
  return `${root}/${path}`;
}

class SignIn extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      hasRequestedCode: false,
      errorMessage: null,
    };
  }
  render() {
    const { hasRequestedCode, errorMessage } = this.state;
    if (hasRequestedCode) {
      return (
        <div>
          <h1>Nice! Okay check your email</h1>
        </div>
      );
    }
    return (
      <div>
        {errorMessage && <h3>{errorMessage}</h3>}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const email = this._emailInput.value;
            this.setState({ hasRequestedCode: true });
            fetch(serverPath("api/magic/request"), {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ email }),
            })
              .then((x) =>
                x.status === 200 ? x.json() : Promise.reject(x.json())
              )
              .catch(() => {
                this.setState({
                  errorMessage:
                    "Uh oh. failed to send you an email. Plz ping Stopa",
                });
              });
          }}
        >
          <input
            type="email"
            placeholder="Enter your email"
            ref={(ref) => {
              this._emailInput = ref;
            }}
          />
          <button type="submit">Send me a magic code!</button>
        </form>
      </div>
    );
  }
}

class ProfileHome extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      errorMessage: null,
      idToGroup: {},
    };
    this._idToGroupRef = {};
    this._userGroupIdsRef = firebase
      .database()
      .ref(`/users/${firebase.auth().currentUser.uid}/groups`);
  }
  componentDidMount() {
    const updateGroups = (f) => {
      this.setState(({ idToGroup }) => ({
        idToGroup: f(idToGroup),
      }));
    };
    const onGroup = (snap) => {
      updateGroups((oldGroups) => ({ ...oldGroups, [snap.key]: snap.val() }));
    };
    this._userGroupIdsRef.on("child_added", (snap) => {
      const groupId = snap.key;
      if (this._idToGroupRef[groupId]) return;
      const newRef = firebase.database().ref(`/groups/${groupId}/`);
      newRef.on("value", onGroup);
      this._idToGroupRef[groupId] = newRef;
    });
    this._userGroupIdsRef.on("child_removed", (snap) => {
      const groupId = snap.key;
      if (!this._idToGroupRef[groupId]) return;
      this._idToGroupRef[groupId].off();
      delete this._idToGroupRef[groupId];
      updateGroups((oldGroups) => {
        const res = { ...oldGroups };
        delete res[groupId];
        return res;
      });
    });
  }

  componentWillUnmount() {
    this._userGroupIdsRef.off();
    Object.values(this._idToGroupRef).forEach((ref) => ref.off());
  }

  render() {
    const { idToGroup, errorMessage } = this.state;
    if (errorMessage) {
      return errorMessage;
    }
    return (
      <div>
        <h2>Groups</h2>
        {Object.entries(idToGroup).map(([k, g]) => {
          return (
            <div key={k}>
              <h4>
                {g.name}{" "}
                {
                  <button
                    onClick={() => {
                      const userKeysToDelete =
                        (g.users &&
                          Object.keys(g.users).reduce((res, uk) => {
                            res[`/users/${uk}/groups/${k}`] = null;
                            return res;
                          }, {})) ||
                        {};
                      firebase
                        .database()
                        .ref()
                        .update({
                          [`/groups/${k}`]: null,
                          ...userKeysToDelete,
                        });
                    }}
                  >
                    delete group!
                  </button>
                }
              </h4>
              <div>
                {g.users &&
                  Object.entries(g.users).map(([uk, u], i, arr) => {
                    return (
                      <p key={u.email}>
                        {u.email}{" "}
                        {arr.length > 1 && (
                          <button
                            onClick={() => {
                              firebase
                                .database()
                                .ref()
                                .update({
                                  [`/groups/${k}/users/${uk}`]: null,
                                  [`/users/${uk}/groups/${k}`]: null,
                                });
                            }}
                          >
                            delete user!
                          </button>
                        )}
                      </p>
                    );
                  })}
              </div>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const email = this._friendEmailInput.value;
                  this._friendEmailInput.value = "";
                  const invitationRef = firebase
                    .database()
                    .ref("/invitations")
                    .push();
                  alert("ok, sent! this will be a cool flash message later");
                  invitationRef
                    .set({
                      "sender-email": firebase.auth().currentUser.email,
                      "receiver-email": email,
                      "group-id": k,
                    })
                    .then((res) => {
                      return firebase.auth().currentUser.getIdToken();
                    })
                    .then((token) => {
                      fetch(serverPath("api/sync"), {
                        method: "POST",
                        headers: {
                          token,
                          "Content-Type": "application/json",
                        },
                        body: JSON.stringify({
                          type: "invite-user",
                          data: { "invitation-id": invitationRef.key },
                        }),
                      }).then((x) => {
                        return x.status === 200
                          ? x.json()
                          : Promise.reject(x.json());
                      });
                    })
                    .then(
                      () => {},
                      () => {
                        this.setState({
                          errorMessage: "oi...failed invite user",
                        });
                      }
                    );
                }}
              >
                <input
                  placeholder="your friend's email"
                  ref={(x) => {
                    this._friendEmailInput = x;
                  }}
                />
                <button type="submit">invite</button>
              </form>
              <hr />
            </div>
          );
        })}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const name = this._groupNameInput.value;
            this._groupNameInput.value = "";
            const groupKey = firebase.database().ref("/groups/").push().key;
            const uid = firebase.auth().currentUser.uid;
            const email = firebase.auth().currentUser.email;
            firebase
              .database()
              .ref()
              .update({
                [`/groups/${groupKey}/name`]: name,
                [`/groups/${groupKey}/users/${uid}/email`]: email,
                [`/users/${uid}/groups/${groupKey}`]: true,
              });
          }}
        >
          <input label="group name" ref={(x) => (this._groupNameInput = x)} />
          <button type="submit">create a group</button>
        </form>
      </div>
    );
  }
}

class Journals extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isLoading: true,
      errorMessage: null,
    };
  }
  render() {
    return <h1>Journals!</h1>;
  }
}


class Account extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isLoading: true,
      errorMessage: null,
    };
  }
  render() {
    return <h1>Account!</h1>;
  }
}

class MeComp extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isLoading: true,
      isLoggedIn: null,
    };
  }

  componentDidMount() {
    window.signOut = () => firebase.auth().signOut();
    firebase.auth().onAuthStateChanged((user) => {
      this.setState({
        isLoading: false,
        isLoggedIn: !!user,
      });
    });
  }

  render() {
    const { isLoading, isLoggedIn } = this.state;
    if (isLoading) {
      return <div>Loading...</div>;
    }
    if (!isLoggedIn) {
      return <SignIn />;
    }
    return (
      <div>
        <Link to="/me">Home</Link>
        {' '}
        <Link to="/me/journals">Journals</Link>
        {' '}
        <Link to="/me/account">Account</Link>
        {' '}
        <button onClick={() => firebase.auth().signOut()}>Sign out</button>
        <Switch>
          <Route path="/me/journals">
            <Journals />
          </Route>
          <Route path="/me/account">
            <Account />
          </Route>
          <Route path="/me">
            <ProfileHome />
          </Route>
        </Switch>
      </div>
    );
  }
}

const Me = withRouter(MeComp);

class MagicAuthComp extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isLoading: true,
      errorMessage: null,
    };
  }

  componentDidMount() {
    const { code } = this.props.match.params;
    fetch(serverPath("api/magic/auth"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    })
      .then((x) => (x.status === 200 ? x.json() : Promise.reject("uh oh")))
      .then(({ token }) => firebase.auth().signInWithCustomToken(token))
      .then(
        () => {
          this.props.history.push("/me");
        },
        () => {
          this.setState({
            isLoading: false,
            errorMessage: "Uh oh. failed to log in please ping Stopa",
          });
        }
      );
  }

  render() {
    const { isLoading, errorMessage } = this.state;
    if (errorMessage) {
      return <div>{errorMessage}</div>;
    }
    if (isLoading) {
      return <div>Loading...</div>;
    }
    return <div>Magic Link worked!</div>;
  }
}

const MagicAuth = withRouter(MagicAuthComp);

function Home() {
  return (
    <div className="Home">
      <h1>journaltogether</h1>
      <h3>Keep track of your days and connect with your friends</h3>
      <ol>
        <li>Choose a few friends</li>
        <li>
          Every evening, each of you will receive an email, asking about your
          day
        </li>
        <li>Each of you write a reflection</li>
        <li>
          The next morning, you'll all receive an email of all reflections
        </li>
      </ol>
      <h4>
        Interested?{" "}
        <a
          href="mailto:stepan.p@gmail.com"
          rel="noopener noreferrer"
          target="_blank"
        >
          send a ping : )
        </a>
      </h4>
    </div>
  );
}

class App extends React.Component {
  render() {
    return (
      <div className="App">
        <Router>
          <Switch>
            <Route path="/me">
              <Me />
            </Route>
            <Route path="/magic/:code">
              <MagicAuth />
            </Route>
            <Route path="/">
              <Home />
            </Route>
          </Switch>
        </Router>
      </div>
    );
  }
}

export default App;
