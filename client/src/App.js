import React from "react";
import "./App.css";
import { withRouter } from "react-router";
import { BrowserRouter as Router, Switch, Route } from "react-router-dom";
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
            fetch(serverPath("api/magic/request"), {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ email }),
            })
              .then((x) =>
                x.status === 200 ? x.json() : Promise.reject("uh oh")
              )
              .then(
                () => {
                  this.setState({ hasRequestedCode: true });
                },
                () => {
                  this.setState({
                    hasRequestedCode: true,
                    errorMessage:
                      "Uh oh. failed to send you an email. Plz ping Stopa",
                  });
                }
              );
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
    const { idToGroup } = this.state;
    return (
      <div>
        <h2>Groups</h2>
        {Object.entries(idToGroup).map(([k, g]) => {
          return (
            <div key={k}>
              <h4>{g.name}</h4>
              <div>
                {g.users &&
                  Object.values(g.users).map((u) => {
                    return (
                      <p key={u.email}>{u.email}</p>
                    );
                  })}
              </div>
            </div>
          );
        })}
      </div>
    );
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
        <button>Journals</button>
        <button>Account</button>
        <button onClick={() => firebase.auth().signOut()}>Sign out</button>
        <Switch>
          <Route path="/">
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
